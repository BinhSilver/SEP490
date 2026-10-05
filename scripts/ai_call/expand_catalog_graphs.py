"""Author connected graphs for the existing AI Call roots.

Run before generate_seed.py. Existing child cases/IDs are retained. This script
upserts its authored nodes/edges; later manual catalog edits remain possible.
The Minna skill map is original practice content, not textbook dialogue.
"""
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / 'data/ai-call/catalog.json'

# code | name | semantic learner evidence | AI line | translation | learner line | translation | icon
STEPS = r'''
MEET_CONFLICT|Trao đổi khi bạn không rảnh|Nói hoặc hỏi giờ khác khi thời gian hẹn chưa phù hợp.|土曜日は予定があります。別の日はどうですか。|Thứ bảy tôi có lịch rồi. Ngày khác thì sao?|では、日曜日はいかがですか。|Vậy chủ nhật thì sao?|calendar-xmark
MEET_CONFIRM|Chốt cuộc hẹn phù hợp|Xác nhận thời gian, nơi gặp hoặc đồng ý hẹn dịp khác.|日曜日の午後なら大丈夫です。どこで会いましょうか。|Chiều chủ nhật thì được. Chúng ta gặp ở đâu?|午後二時に駅の前で会いましょう。|Hai giờ chiều gặp trước ga nhé.|calendar-check
NAME_REPEAT|Nhờ nghe hoặc nói lại tên|Nói rõ tên đúng hoặc xin nhắc lại khi chưa nghe rõ, không nhận một tên bị nhầm.|お名前をもう一度お願いできますか。|Bạn có thể nói lại tên không?|ミンです。ミ、ンです。|Tôi là Minh. Mi-n.|id-card
MEET_EXPERIENCE|Kể trải nghiệm và hỏi lại|Kể một hoạt động từng thử và hỏi đối phương đã thử chưa.|何か新しいことをしたことがありますか。|Bạn đã từng thử điều gì mới chưa?|日本でキャンプをしたことがあります。あなたはどうですか。|Tôi từng cắm trại ở Nhật. Bạn thì sao?|comments
TICKET_HELP|Xin hướng dẫn mua vé|Nêu điểm đến và nhờ hướng dẫn thao tác mua vé.|券売機の使い方がわかりませんか。|Bạn chưa biết dùng máy bán vé phải không?|渋谷までの切符を買いたいです。使い方を教えてください。|Tôi muốn mua vé đến Shibuya. Xin chỉ cách dùng.|ticket
TICKET_CHECK|Kiểm tra vé vừa mua|Đối chiếu điểm đến hoặc số tiền trên vé với hướng dẫn; nếu chọn nhầm thì nêu rõ bước bị nhầm.|まず行き先を選んでください。切符の行き先は合っていますか。|Trước tiên hãy chọn điểm đến. Điểm đến trên vé đúng chưa?|渋谷ですね。合っています。ありがとうございます。|Shibuya phải không. Đúng rồi. Cảm ơn.|clipboard-check
SIGN_MEANING|Hỏi ý nghĩa biển báo|Chỉ biển chưa hiểu và hỏi ý nghĩa trước khi đi tiếp.|どの案内がわかりませんか。|Bạn chưa hiểu biển hướng dẫn nào?|この「立入禁止」はどういう意味ですか。|Biển “cấm vào” này có nghĩa gì?|signs-post
SIGN_ROUTE|Chọn lối đi đúng|Xác nhận khu vực không được vào và hỏi hoặc nhắc lại lối được đi.|ここには入れません。改札へはこちらから行ってください。|Không được vào đây. Đi đến cổng soát vé theo lối này.|こちらから改札へ行けばいいですね。|Đi đến cổng soát vé theo lối này là được phải không?|route
ACCESS_HELP|Xin lối đi phù hợp với hành lý|Nêu khó khăn với hành lý hoặc bậc thang và hỏi đường có thang máy.|お荷物が多いですね。何かお手伝いしましょうか。|Hành lý của bạn khá nhiều. Tôi có thể giúp gì không?|荷物が重いので、エレベーターはどこですか。|Hành lý nặng nên cho tôi hỏi thang máy ở đâu?|elevator
ACCESS_CONFIRM|Xác nhận đường tới sân ga|Nhắc lại đường có thể đi với hành lý và sân ga đã được hướng dẫn.|右の通路にエレベーターがあります。その先が二番線です。|Lối bên phải có thang máy. Qua đó là sân ga số hai.|右のエレベーターで二番線へ行きます。ありがとうございます。|Tôi đi thang máy bên phải đến sân ga số hai. Cảm ơn.|map-location-dot
ITEM_IDENTIFY|Chỉ sản phẩm muốn tìm|Chỉ hoặc mô tả sản phẩm, hỏi tên hoặc vị trí của nó.|どの商品をお探しですか。|Bạn đang tìm sản phẩm nào?|この写真の飲み物はありますか。|Có đồ uống trong ảnh này không?|basket-shopping
ITEM_SUBSTITUTE|Chọn hàng thay thế|Hỏi lựa chọn còn hàng và chọn rõ sản phẩm hoặc quyết định chưa mua.|この商品は売り切れですが、こちらの小さいサイズはあります。|Sản phẩm này đã hết, nhưng còn loại dung tích nhỏ ở đây.|では、小さいほうを二つお願いします。|Vậy cho tôi hai chai loại nhỏ.|arrows-rotate
COUNT_FIX|Sửa số lượng hàng|Nêu lại số lượng đúng khi giỏ hàng hoặc nhân viên xác nhận nhầm.|こちらを三つですね。|Ba cái loại này phải không?|いいえ、二つです。|Không, hai cái ạ.|list-ol
UTENSIL_NAME|Hỏi tên và xin dụng cụ|Hỏi tên hoặc xin đúng dụng cụ cần dùng, kèm số lượng nếu cần.|お箸とスプーンがあります。どちらが必要ですか。|Có đũa và muỗng. Bạn cần loại nào?|スプーンを一つお願いします。|Cho tôi một muỗng.|utensils
CLOTHES_BUDGET|Nêu ngân sách và công dụng|Nói khoảng giá hoặc mục đích sử dụng để nhân viên đề xuất đúng sản phẩm.|どのくらいのご予算ですか。|Ngân sách của bạn khoảng bao nhiêu?|仕事で着るために、五千円以下のジャケットを探しています。|Tôi tìm áo khoác dưới 5.000 yên để mặc đi làm.|coins
CLOTHES_COMPARE|So sánh rồi chọn mẫu|Hỏi hoặc xác nhận điểm khác nhau về giá, chất liệu hay công dụng và chọn mẫu để thử.|こちらは四千円です。もう一つは六千円で、少し暖かいです。|Mẫu này 4.000 yên. Mẫu kia 6.000 yên và ấm hơn một chút.|四千円のほうを試着してもいいですか。|Tôi thử mẫu 4.000 yên được không?|shirt
FIT_REPORT|Báo áo quá chật hoặc quá rộng|Nói cụ thể chỗ chưa vừa sau khi thử và yêu cầu cỡ hoặc mẫu khác.|着てみて、いかがですか。|Sau khi mặc thử, bạn thấy thế nào?|肩がきつすぎます。大きいサイズはありますか。|Vai chật quá. Có cỡ lớn hơn không?|ruler-combined
FIT_RECHECK|Kiểm tra cỡ hoặc mẫu mới|Xác nhận độ vừa của sản phẩm được thay và quyết định mua hoặc tiếp tục tìm.|こちらのサイズも試してみてください。今度はいかがですか。|Xin thử cỡ này nữa. Lần này thế nào?|今度はちょうどいいです。こちらにします。|Lần này vừa rồi. Tôi chọn mẫu này.|user-check
CLINIC_PAPERS|Hỏi giấy tờ cần mang|Hỏi giấy tờ cần có hoặc báo giấy tờ còn thiếu trước buổi hẹn.|当日、必要な書類についてご質問はありますか。|Bạn có câu hỏi về giấy tờ cần mang hôm đó không?|何を持って行かなければなりませんか。|Tôi cần mang những gì?|file-medical
CLINIC_NEXT|Chốt cách bổ sung giấy tờ|Xác nhận sẽ kiểm tra, mang hoặc bổ sung giấy tờ theo hướng dẫn cụ thể của lễ tân.|必要なものを確認してご案内します。お持ちでない書類はありますか。|Chúng tôi sẽ kiểm tra và hướng dẫn những thứ cần mang. Có giấy tờ nào bạn chưa có không?|まだ持っていない書類があります。後で提出できるか確認してください。|Có giấy tờ tôi chưa có. Xin kiểm tra có thể nộp bổ sung sau không.|clipboard-check
CLINIC_HOURS|Xác minh giờ tiếp nhận|Hỏi giờ tiếp nhận trực tiếp thay vì coi tin nghe được là chắc chắn.|受付時間についてのお問い合わせでしょうか。|Bạn muốn hỏi giờ tiếp nhận phải không?|受付時間が変わったそうですが、本当ですか。|Tôi nghe giờ tiếp nhận thay đổi. Có đúng không?|clock
CLINIC_TIME_CHECK|Đối chiếu lịch hẹn với giờ mới|Nhắc lại giờ đã được lễ tân xác nhận và hỏi lịch hẹn của mình có bị ảnh hưởng không.|本日は午後五時まで受け付けます。予約のお時間を教えてください。|Hôm nay nhận khách đến 5 giờ chiều. Xin cho biết giờ hẹn của bạn.|四時に予約しています。その時間で大丈夫ですか。|Tôi hẹn lúc 4 giờ. Giờ đó vẫn được phải không?|calendar-check
MED_REPEAT|Nhờ giải thích lại hướng dẫn|Nêu đúng phần hướng dẫn dùng thuốc chưa hiểu và nhờ dược sĩ kiểm tra, giải thích.|説明のどの部分がわかりにくいですか。|Phần nào trong hướng dẫn bạn thấy khó hiểu?|飲み方がよくわかりません。もう一度説明してもらえますか。|Tôi chưa rõ cách dùng. Có thể giải thích lại một lần nữa không?|circle-question
MED_LABEL|Đối chiếu với nhãn thuốc|Xin dược sĩ chỉ phần hướng dẫn trên nhãn hoặc xác nhận lại điều chưa rõ; không tự suy ra liều dùng.|実際のお薬の表示を一緒に確認しましょう。|Chúng ta cùng kiểm tra nhãn của thuốc thực tế nhé.|表示のどこを見ればいいですか。確認をお願いします。|Tôi cần xem phần nào trên nhãn? Xin kiểm tra giúp.|prescription-bottle-medical
MED_UNCERTAIN|Báo thông tin chưa đủ để chọn thuốc|Nói chưa nhớ tên thuốc đang dùng hoặc chưa rõ thông tin cần thiết, không giả vờ biết.|今飲んでいるお薬の名前はわかりますか。|Bạn biết tên thuốc đang dùng không?|名前がわかりません。家に説明書があります。|Tôi không biết tên. Tôi có tờ hướng dẫn ở nhà.|file-circle-question
MED_FOLLOWUP|Thống nhất cách kiểm tra thêm|Đề nghị cung cấp nhãn/tên thuốc hoặc hỏi chuyên môn trước khi quyết định mua hay dùng cùng.|名前や表示がわかったら、もう一度確認しましょう。|Khi biết tên hoặc có nhãn, chúng ta kiểm tra lại nhé.|説明書を持って来ます。それから相談してもいいですか。|Tôi sẽ mang tờ hướng dẫn đến. Sau đó tư vấn tiếp được không?|user-doctor
MEETING_PREPARED|Kiểm tra tài liệu và thiết bị|Nêu phần đã chuẩn bị và hỏi phần còn thiếu trước cuộc họp.|会議の準備はどこまでできていますか。|Việc chuẩn bị cuộc họp đến đâu rồi?|資料は送ってあります。会議室も予約してあります。|Tài liệu đã gửi. Phòng họp cũng đã đặt.|file-lines
MEETING_BACKUP|Thống nhất cách xử lý phần thiếu|Đề xuất cách chuẩn bị phần thiếu hoặc phương án thiết bị thay thế và xác nhận người thực hiện.|プロジェクターが使えないようです。どうしましょうか。|Có vẻ máy chiếu không dùng được. Chúng ta làm thế nào?|オンラインで画面を共有しましょう。接続を確認しておきます。|Chúng ta chia sẻ màn hình trực tuyến. Tôi sẽ kiểm tra kết nối trước.|laptop
LEAVE_REQUEST|Xin rời sớm hoặc đổi ca|Nêu lý do, thời gian xin rời và hỏi người có thẩm quyền cho phép.|何時から席を外したいですか。|Bạn muốn rời chỗ từ mấy giờ?|通院のため、三時から早退させていただけませんか。|Vì phải đi khám, tôi xin phép về sớm từ 3 giờ được không?|calendar-xmark
LEAVE_HANDOVER|Xác nhận bàn giao trước khi rời|Nêu ai tiếp nhận phần việc hoặc cách hoàn thành việc gấp và xác nhận sự đồng ý của người phụ trách.|午後の会議の引き継ぎはどうしますか。|Bạn sẽ bàn giao cuộc họp buổi chiều thế nào?|資料は先に送ります。会議の担当は田中さんに確認します。|Tôi sẽ gửi tài liệu trước. Tôi sẽ xác nhận với anh Tanaka việc phụ trách cuộc họp.|handshake
CONTACT_PERSON|Hỏi người phụ trách có tiện nghe|Nêu người muốn liên hệ và dùng cách hỏi lịch sự phù hợp.|どなたにお取り次ぎしましょうか。|Bạn muốn tôi chuyển máy cho ai?|田中さんはいらっしゃいますか。会議の日程について相談したいです。|Anh Tanaka có ở đó không? Tôi muốn trao đổi lịch họp.|phone
CONTACT_MESSAGE|Để lời nhắn hoặc hẹn gọi lại|Nêu nội dung lời nhắn hoặc thời gian gọi lại, xác nhận tên và liên hệ nếu cần.|田中は席を外しております。伝言を承りましょうか。|Anh Tanaka đang vắng mặt. Tôi nhận lời nhắn giúp được không?|三時にもう一度お電話いたします。そうお伝えください。|Tôi sẽ gọi lại lúc 3 giờ. Xin chuyển lời giúp.|comment-dots
MAKEUP_PLAN|Đề xuất lịch học bù|Nêu bài cần học bù và thời gian dự định làm, dựa trên phần đã bỏ lỡ.|どの課題から取り組めそうですか。|Bạn có thể bắt đầu từ bài tập nào?|今日は十ページを読んでから、練習問題をします。|Hôm nay em đọc trang 10 rồi làm bài luyện.|book-open
MAKEUP_CONFIRM|Chốt lượng bài và hạn hợp lý|Xin điều chỉnh khối lượng hoặc xác nhận lịch học bù có thể thực hiện.|全部を明日までに終えられそうですか。|Em có thể làm xong tất cả trước ngày mai không?|一度に全部は難しいです。金曜日までに提出してもいいですか。|Làm hết một lần thì khó. Em nộp trước thứ sáu được không?|calendar-check
HOMEWORK_HELP|Chỉ phần bài chưa hiểu|Nêu trang hoặc nội dung cụ thể chưa hiểu và xin hướng dẫn thay vì yêu cầu làm hộ.|どこが難しいですか。|Phần nào khó với em?|この文の意味がわかりません。説明していただけませんか。|Em chưa hiểu nghĩa câu này. Thầy cô giải thích giúp được không?|circle-question
STUDY_HABIT|Chọn cách tự luyện và báo lại|Nêu cách luyện đều đặn hoặc cách tự kiểm tra sau khi được hướng dẫn.|説明を聞いて、次はどう練習しますか。|Sau khi nghe giải thích, em sẽ luyện thế nào?|毎日一つ例文を作るようにしています。明日、見てもらえますか。|Em đang cố viết một câu ví dụ mỗi ngày. Ngày mai thầy cô xem giúp được không?|pen-to-square
HOTEL_PERMISSION|Xin dùng dịch vụ hoặc gửi đồ|Nêu dịch vụ muốn dùng và hỏi điều kiện trước khi thực hiện.|どのサービスをご利用になりたいですか。|Bạn muốn dùng dịch vụ nào?|チェックインの前に荷物を預けてもいいですか。|Tôi gửi hành lý trước giờ nhận phòng được không?|suitcase
HOTEL_CONDITION|Xác nhận điều kiện dịch vụ|Hỏi hoặc xác nhận giờ nhận lại, phí và giới hạn dịch vụ theo thông tin lễ tân đã kiểm tra.|お預かりできるか確認します。何時に戻られますか。|Chúng tôi kiểm tra xem có nhận giữ được không. Bạn quay lại lúc mấy giờ?|午後三時に戻ります。料金がかかるか教えてください。|Tôi quay lại lúc 3 giờ chiều. Xin cho biết có mất phí không.|clock
WIFI_REPORT|Mô tả thao tác Wi-Fi chưa thành công|Nêu điều đã thử và vấn đề cụ thể khi dùng Wi-Fi, không hỏi chung chung.|どの操作で接続できませんでしたか。|Bạn không kết nối được ở thao tác nào?|案内のとおりにパスワードを入れましたが、接続できません。|Tôi nhập mật khẩu theo hướng dẫn nhưng không kết nối được.|wifi
WIFI_RECHECK|Thử hướng dẫn rồi kiểm tra lại|Thực hiện hoặc xác nhận kết quả sau khi được hướng dẫn; nếu vẫn lỗi thì báo đúng lỗi còn lại.|ネットワーク名を確認して、もう一度試していただけますか。|Bạn kiểm tra tên mạng rồi thử lại một lần nữa được không?|もう一度やってみました。今は接続できます。|Tôi thử lại rồi. Giờ kết nối được.|clipboard-check
LOST_REPORT|Báo đồ để quên ở khách sạn|Nêu đồ bị quên, số phòng hoặc thời điểm có thể đã để lại.|何を、どちらにお忘れですか。|Bạn quên đồ gì, ở đâu?|今朝、二〇三号室に黒いバッグを忘れてしまいました。|Sáng nay tôi quên túi đen trong phòng 203.|suitcase
LOST_IDENTIFY|Xác minh đặc điểm đồ vật|Cung cấp đặc điểm phân biệt đồ của mình để lễ tân kiểm tra, không nhận đồ chưa xác minh.|確認のため、バッグの特徴を教えてください。|Để kiểm tra, xin cho biết đặc điểm của túi.|持ち手が赤くて、中に青いノートがあります。|Quai màu đỏ và trong túi có sổ màu xanh.|id-card
LOST_PICKUP|Chốt cách nhận hoặc chờ thông báo|Nếu đã tìm thấy thì xác nhận thời gian nhận và điều kiện xác minh; nếu chưa thấy thì để lại cách liên hệ.|確認できたらご連絡します。受け取りについてご希望はありますか。|Khi kiểm tra được chúng tôi sẽ liên hệ. Bạn muốn nhận theo cách nào?|見つかったら連絡してください。今日の五時に取りに来られます。|Nếu tìm thấy xin liên hệ. Hôm nay tôi có thể đến nhận lúc 5 giờ.|calendar-check
ROOM_RECHECK|Kiểm tra phòng sau hỗ trợ|Xác nhận sự cố đã hết hoặc báo biểu hiện còn lại sau khi sửa/đổi phòng.|新しいお部屋では、お湯は使えますか。|Ở phòng mới, bạn dùng được nước nóng chưa?|今はお湯が出ます。ありがとうございます。|Giờ có nước nóng rồi. Cảm ơn.|faucet-drip
JOB_ABILITY|Nói khả năng gắn với công việc|Nêu việc làm được và một ví dụ hoặc giới hạn thực tế.|どのような仕事ができますか。|Bạn có thể làm những công việc nào?|簡単な接客ができますが、レジはまだ使ったことがありません。|Tôi phục vụ khách đơn giản được, nhưng chưa dùng máy tính tiền.|id-badge
JOB_PREPARE|Hỏi cách chuẩn bị và học việc|Nêu việc cần học thêm và hỏi việc cần làm trước ca đầu.|初めての作業は、どう覚えたいですか。|Bạn muốn học thao tác mới bằng cách nào?|説明を聞いてから練習したいです。初日の前に何を準備しますか。|Tôi muốn nghe giải thích rồi thực hành. Trước ngày đầu cần chuẩn bị gì?|book-open
JOB_DOCS|Báo tài liệu chưa đủ|Nói rõ tài liệu đã có hoặc còn thiếu, không nói đã nộp nếu chưa nộp.|応募書類はすべてそろっていますか。|Hồ sơ ứng tuyển đủ hết chưa?|履歴書はありますが、写真を忘れてしまいました。|Tôi có sơ yếu lý lịch nhưng quên ảnh.|file-circle-question
JOB_DOCS_CONFIRM|Xác nhận cách bổ sung hồ sơ|Xin bổ sung tài liệu bằng thời gian/cách gửi cụ thể và xác nhận phía tuyển dụng chấp nhận.|不足分はいつ提出できますか。|Bạn có thể nộp phần thiếu khi nào?|明日の午前中に持参いたします。それでよろしいでしょうか。|Tôi sẽ mang đến sáng mai. Như vậy có được không?|file-circle-check
REPAIR_AGAIN|Báo lỗi tái diễn sau sửa|Nêu ngày sửa, biểu hiện lỗi quay lại và thao tác đã kiểm tra.|修理後、どのような問題が起きましたか。|Sau khi sửa, có vấn đề gì xảy ra?|先週直してもらったばかりなのに、また音が出ません。|Vừa được sửa tuần trước mà lại không có tiếng.|triangle-exclamation
REPAIR_FOLLOW|Chốt việc kiểm tra lại và thông báo|Hỏi cách tiếp nhận kiểm tra lại, thời gian dự kiến hoặc cách được báo kết quả; không giả định được đổi/hoàn tiền.|再確認の手順をご案内します。ご希望を教えてください。|Chúng tôi hướng dẫn cách kiểm tra lại. Xin cho biết mong muốn của bạn.|再点検をお願いします。結果と費用がわかったら連絡してください。|Xin kiểm tra lại. Khi biết kết quả và chi phí thì liên hệ giúp.|screwdriver-wrench
RETURN_CONDITION|Làm rõ điều kiện đổi hoặc hoàn|Hỏi điều kiện cụ thể theo tình trạng sản phẩm và bằng chứng mua hàng.|どの対応をご希望ですか。条件を確認いたします。|Bạn muốn cách xử lý nào? Chúng tôi sẽ kiểm tra điều kiện.|交換できない場合は、返金を相談できますか。|Nếu không đổi được thì có thể trao đổi việc hoàn tiền không?|scale-balanced
RETURN_CONFIRM|Chọn phương án được xác nhận|Chọn phương án sau khi nhân viên kiểm tra điều kiện và xác nhận bước tiếp theo.|返金はまだ確認が必要です。取り寄せなら手配できます。|Hoàn tiền vẫn cần kiểm tra. Chúng tôi có thể đặt hàng thay thế.|では、取り寄せをお願いします。届く予定日を教えてください。|Vậy xin đặt hàng thay thế. Cho tôi biết ngày dự kiến hàng đến.|handshake
RESOURCE_GAP|Báo thiếu nguồn lực|Nêu phần việc thiếu người/thời gian và ảnh hưởng cụ thể, không chỉ nói bận.|予定どおり進めるために、不足しているものは何ですか。|Để làm đúng kế hoạch, hiện đang thiếu gì?|テスト担当が一人不足しており、確認に二日余分にかかる見込みです。|Thiếu một người kiểm thử nên dự kiến việc kiểm tra mất thêm hai ngày.|user-group
RESOURCE_PLAN|Thống nhất ưu tiên và người phụ trách|Đề xuất phân công hoặc thu hẹp việc ưu tiên, kèm mốc cập nhật cụ thể.|体制と優先順位をどう調整しますか。|Chúng ta điều chỉnh nhân lực và ưu tiên thế nào?|重要な機能を先に確認し、残りの担当と期限を今日中に決めたいです。|Tôi muốn kiểm tra chức năng quan trọng trước, chốt người phụ trách và hạn phần còn lại trong hôm nay.|list-check
REVIEW_CHANGE|Làm rõ phản hồi sau bàn giao|Nêu phản hồi hoặc hỏi yêu cầu nào khác với phạm vi đã thống nhất.|提出物について、追加の修正依頼がありました。|Có yêu cầu sửa thêm đối với phần đã bàn giao.|合意した範囲との差分と、必須の修正を確認させてください。|Xin cho tôi xác nhận phần khác với phạm vi đã thống nhất và những sửa đổi bắt buộc.|file-lines
REVIEW_COMMIT|Chốt phạm vi sửa và lịch phản hồi|Xác nhận sửa phần nào, phần nào phải thương lượng riêng và khi nào báo lại.|今回の修正範囲と対応日を確認しましょう。|Chúng ta xác nhận phạm vi sửa lần này và ngày xử lý nhé.|不具合の修正を優先し、新しい機能の期限は別途相談します。明日、見積もりを報告します。|Tôi ưu tiên sửa lỗi, hạn cho chức năng mới sẽ trao đổi riêng. Ngày mai tôi báo ước lượng.|calendar-check
ROOM_REQUIREMENTS|Mô tả phòng muốn tìm|Nêu điều kiện về vị trí, giá hoặc tiện ích và thứ ưu tiên, dùng cách nói đơn giản.|どんな部屋をお探しですか。|Bạn đang tìm phòng như thế nào?|駅から近くて、家賃が六万円以下の部屋を探しています。|Tôi tìm phòng gần ga, tiền thuê dưới 60.000 yên.|house
ROOM_COMPARE|Chọn điều kiện ưu tiên và hẹn xem|So sánh lựa chọn được đưa ra, điều chỉnh điều kiện hoặc hỏi lịch xem phòng; chưa coi hỏi thông tin là ký hợp đồng.|近い部屋は少し高いです。少し離れた部屋ならご予算内です。|Phòng gần ga hơi đắt. Phòng xa hơn một chút thì trong ngân sách.|予算を優先します。離れた部屋を見せてもらえますか。|Tôi ưu tiên ngân sách. Có thể cho xem phòng xa hơn không?|scale-balanced
HOUSE_RULE|Hỏi cách thực hiện nội quy|Nêu việc chưa biết cách làm và xin hướng dẫn cụ thể từ quản lý, không tự bịa quy định địa phương.|入居後のことで、ご不明な点はありますか。|Có điều gì sau khi vào ở bạn chưa rõ không?|ごみの出し方がわかりません。どこで確認したらいいですか。|Tôi chưa biết cách bỏ rác. Tôi nên kiểm tra ở đâu?|circle-info
HOUSE_RULE_CHECK|Xác nhận hướng dẫn và chỗ tra cứu|Nhắc lại nơi xem hướng dẫn hoặc đề nghị văn bản trước khi làm theo.|建物の案内と自治体の説明を確認しましょう。|Chúng ta kiểm tra hướng dẫn của tòa nhà và địa phương nhé.|案内を確認してから出します。説明書をいただけますか。|Tôi sẽ kiểm tra hướng dẫn rồi mới bỏ rác. Có thể cho tôi tờ hướng dẫn không?|clipboard-check
RENT_RECHECK|Kiểm tra thiết bị sau sửa|Nói thiết bị đã hoạt động hay vẫn còn lỗi sau khi nhân viên thực hiện hỗ trợ.|点検後、エアコンは動くようになりましたか。|Sau khi kiểm tra, điều hòa hoạt động được chưa?|まだ動きません。もう一度確認していただけますか。|Vẫn chưa hoạt động. Có thể kiểm tra lại một lần nữa không?|screwdriver-wrench
RENT_NEXT|Xác nhận việc còn lại và điều kiện|Đề nghị cách xử lý tiếp theo, người liên hệ và thông báo chi phí trước khi làm tiếp.|担当者に再確認します。次の対応についてご希望はありますか。|Chúng tôi sẽ nhờ người phụ trách kiểm tra lại. Bạn muốn gì cho bước xử lý tiếp theo?|訪問時間と費用がわかったら、作業の前に連絡してください。|Khi biết giờ đến và chi phí thì liên hệ trước khi làm giúp.|phone
RESTAURANT_HOURS|Hỏi giờ nhận gọi món|Hỏi giờ còn nhận gọi món hoặc có đủ thời gian dùng bữa theo giờ quán xác nhận.|本日は九時まで営業しています。何時ごろいらっしゃいますか。|Hôm nay quán mở đến 9 giờ. Bạn đến khoảng mấy giờ?|八時半に行く予定です。まだ注文できますか。|Tôi dự định đến 8 giờ rưỡi. Vẫn gọi món được không?|clock
RESTAURANT_TIME|Chọn cách dùng bữa phù hợp giờ quán|Nếu sát giờ đóng cửa thì xác nhận gọi món nhanh hoặc quyết định quay lại sau, dựa trên điều kiện quán đã nói.|ラストオーダーは八時四十五分です。ご注文を先に決めますか。|Lần gọi món cuối lúc 8 giờ 45. Bạn muốn chọn món trước không?|では、先にメニューを見てもいいですか。|Vậy tôi xem thực đơn trước được không?|book-open
MENU_COMPARE|So sánh món trước khi chọn|Hỏi sự khác nhau về vị, độ cay hoặc phần ăn của hai món để chọn phù hợp.|うどんとカレーで迷っていますか。|Bạn đang phân vân giữa udon và cà ri phải không?|どちらが辛いですか。辛くないものを食べたいです。|Món nào cay? Tôi muốn ăn món không cay.|scale-balanced
MENU_CHOOSE|Chọn món theo nhu cầu|Chọn rõ món phù hợp sau khi quán giải thích, không để quán quyết định thay mình.|カレーのほうが辛いです。うどんは辛くありません。|Cà ri cay hơn. Udon không cay.|では、うどんを一つお願いします。|Vậy cho tôi một phần udon.|utensils
MENU_APPEARANCE|Hỏi vị khi món trông có vẻ cay|Nêu nhận xét chưa chắc chắn và hỏi quán trước khi quyết định.|こちらの料理について、気になることはありますか。|Có điều gì về món này bạn muốn hỏi không?|辛そうですが、あまり辛くしないで作れますか。|Trông có vẻ cay, có thể làm không quá cay không?|pepper-hot
MENU_CONDITION|Làm rõ yêu cầu có thực hiện được|Hỏi khả năng giảm cay/bỏ nguyên liệu và xác nhận chọn món khác nếu không đáp ứng được.|辛さを変えられるか、厨房に確認します。難しい場合はどうなさいますか。|Chúng tôi kiểm tra với bếp xem đổi độ cay được không. Nếu khó thì bạn muốn thế nào?|辛さを変えられないなら、うどんにします。|Nếu không đổi độ cay được thì tôi chọn udon.|sliders
'''

SPECS = {
 'first-meeting-introduction': dict(opening='初めまして。少しお話ししませんか。',
   branches=[('meet-reschedule','Bạn không rảnh giờ hẹn','INVITE',['MEET_CONFLICT','MEET_CONFIRM'],'SHARED_CONFIRM','Người bạn nói giờ được mời chưa phù hợp'),
             ('name-repeat','Tên chưa nghe rõ','GREETING',['NAME_REPEAT'],'GREETING','Hai người cần làm rõ tên vừa nghe'),
             ('experience','Trao đổi trải nghiệm','HOBBY',['MEET_EXPERIENCE'],'HOBBY','Người học muốn kể trải nghiệm và hỏi lại')],
   links=[('RECIPROCATE','SHARE','Sau khi sửa tên, muốn trao đổi sở thích'),('HOBBY','SHARE','Muốn tìm sở thích chung')]),
 'station-directions-and-transfer': dict(opening='どちらまで行かれますか。',
   branches=[('ticket-help','Chưa biết mua vé','LINE',['TICKET_HELP','TICKET_CHECK'],'LINE','Khách cần hướng dẫn máy bán vé'),
             ('sign-help','Biển báo chưa hiểu','PLATFORM',['SIGN_MEANING','SIGN_ROUTE'],'PLATFORM','Khách hỏi nghĩa biển trên lối tới sân ga'),
             ('luggage-access','Hành lý khó di chuyển','PLATFORM',['ACCESS_HELP','ACCESS_CONFIRM'],'PLATFORM','Khách cần lối có thang máy')],
   links=[('VERIFY','NEXT_TRAIN','Đã đổi đúng tuyến nhưng lỡ chuyến'),('PLAN','PLATFORM','Cần hỏi sân ga của chuyến mới')]),
 'convenience-store-checkout': dict(opening='お買い物ですか。お困りのことはありますか。',
   branches=[('stock-substitute','Không tìm thấy sản phẩm','INTAKE',['ITEM_IDENTIFY','ITEM_SUBSTITUTE'],'CHECKOUT','Khách muốn tìm sản phẩm hoặc hỏi hàng thay thế'),
             ('quantity-fix','Nhân viên xác nhận nhầm số lượng','CHECKOUT',['COUNT_FIX','UTENSIL_NAME'],'CHECKOUT','Khách sửa số lượng hoặc cần dụng cụ trước khi thanh toán')],
   links=[('HEATING','DECLINE_BAG','Đã chọn hâm nóng nhưng không lấy túi'),('ASK_CHOPSTICKS','BAG_PAYMENT','Đã nhận đũa, đến bước trả tiền')]),
 'clothes-shopping-size-fitting': dict(opening='お探しの服はありますか。それとも交換のご相談ですか。',
   branches=[('budget-purpose','Chọn đồ theo ngân sách và công dụng','FINDING',['CLOTHES_BUDGET','CLOTHES_COMPARE'],'SIZE','Khách hỏi đồ phù hợp công dụng hoặc khoảng giá'),
             ('fit-feedback','Thử rồi thấy quá chật hoặc rộng','FITTING',['FIT_REPORT','FIT_RECHECK'],'FITTING','Khách phản hồi độ vừa sau khi thử')],
   links=[('DECIDE','FITTING','Muốn kiểm tra và chốt quyết định ở bước chung'),('CONFIRM_SIZE','ASK_SIZE','Cỡ cần đổi không còn, muốn hỏi mẫu khác')]),
 'clinic-appointment': dict(opening='ご予約でしょうか。予約の変更でしょうか。',
   branches=[('missing-papers','Chưa rõ hoặc thiếu giấy tờ','BOOKING',['CLINIC_PAPERS','CLINIC_NEXT'],'BOOKING','Khách hỏi chuẩn bị giấy tờ trước buổi hẹn'),
             ('verify-hours','Nghe tin đổi giờ tiếp nhận','INTAKE',['CLINIC_HOURS','CLINIC_TIME_CHECK'],'BOOKING','Khách muốn xác minh giờ tiếp nhận và lịch của mình')],
   links=[('CONFIRM_BOOKING','REQUEST_CHANGE','Khách cần đổi lịch vừa xác nhận'),('VERIFY_CHANGE','CLINIC_PAPERS','Sau đổi lịch muốn hỏi giấy tờ cần mang')]),
 'pharmacy-cold-medicine': dict(opening='どのようなご相談でしょうか。',
   branches=[('repeat-instructions','Chưa hiểu hướng dẫn trên nhãn','USAGE',['MED_REPEAT','MED_LABEL'],'USAGE','Khách cần giải thích lại phần hướng dẫn chưa rõ'),
             ('missing-medication-info','Chưa nhớ tên thuốc đang dùng','DISCLOSE',['MED_UNCERTAIN','MED_FOLLOWUP'],'SHARED_CONFIRM','Khách chưa có thông tin thuốc khác, thống nhất kiểm tra tiếp trước khi mua/dùng')],
   links=[('REQUEST','EXPLAIN_DRIVING','Khách bổ sung việc phải lái xe'),('USAGE','DISCLOSE','Khách bổ sung đang dùng thuốc khác')]),
 'office-reschedule-meeting': dict(opening='会議について、何を相談したいですか。',
   branches=[('preparation','Thiếu thiết bị hoặc tài liệu','CONFIRM',['MEETING_PREPARED','MEETING_BACKUP'],'CONFIRM','Cần kiểm tra chuẩn bị hoặc đổi phương án thiết bị'),
             ('leave-handover','Xin rời sớm và bàn giao','INTAKE',['LEAVE_REQUEST','LEAVE_HANDOVER'],'SHARED_CONFIRM','Khách cần xin phép người phụ trách rời sớm, không tự cho rằng đồng nghiệp có quyền duyệt'),
             ('leave-message','Người phụ trách đang vắng','INTAKE',['CONTACT_PERSON','CONTACT_MESSAGE'],'SHARED_CONFIRM','Cần liên hệ người phụ trách để đổi lịch, có thể để lời nhắn')],
   links=[('COMMON_TIME','REQUEST_ONLINE','Đã tìm giờ chung nhưng cần họp trực tuyến'),('PREPARE','MEETING_PREPARED','Sau khi chốt công cụ cần kiểm tra tài liệu')]),
 'school-absence-and-homework': dict(opening='お名前とクラス、それからご用件を教えてください。',
   branches=[('makeup-plan','Học bù nhưng thời gian chưa đủ','HOMEWORK',['MAKEUP_PLAN','MAKEUP_CONFIRM'],'HOMEWORK','Khách muốn lập lịch hoặc giảm khối lượng học bù'),
             ('homework-help','Bài bỏ lỡ chưa hiểu','HOMEWORK',['HOMEWORK_HELP','STUDY_HABIT'],'HOMEWORK','Khách chỉ rõ phần chưa hiểu và xin cách tự luyện')],
   links=[('CONFIRM_DEADLINE','MAKEUP_PLAN','Sau gia hạn muốn lập lịch học bù'),('CATCH_UP','HOMEWORK_HELP','Đã biết bài bỏ lỡ nhưng chưa hiểu nội dung')]),
 'hotel-check-in': dict(opening='チェックインでしょうか。それとも何かお困りですか。',
   branches=[('service-permission','Xin gửi hành lý hoặc dùng dịch vụ','INFO',['HOTEL_PERMISSION','HOTEL_CONDITION'],'INFO','Khách hỏi điều kiện dịch vụ trước khi sử dụng'),
             ('wifi-help','Wi-Fi làm theo hướng dẫn vẫn lỗi','INFO',['WIFI_REPORT','WIFI_RECHECK'],'INFO','Khách báo kết nối Wi-Fi chưa thành công'),
             ('lost-belongings','Để quên đồ sau trả phòng','INTAKE',['LOST_REPORT','LOST_IDENTIFY','LOST_PICKUP'],'SHARED_CONFIRM','Khách báo đồ để quên; không yêu cầu khách nhận phòng lại'),
             ('room-recheck','Kiểm tra phòng sau đổi hoặc sửa','ACCEPT_SOLUTION',['ROOM_RECHECK'],'INFO','Phòng đã được đổi hoặc hỗ trợ, cần kiểm tra kết quả')],
   links=[('CONFIRM_ROOM','STAY','Đã tìm thấy đặt phòng, tiếp tục xác nhận lưu trú'),('INFO','REPORT','Khách báo sự cố phòng vừa nhận'),('ROOM_RECHECK','REPORT','Khách nói phòng sau hỗ trợ vẫn có vấn đề')]),
 'part-time-job-interview': dict(opening='本日はよろしくお願いします。まず、ご希望のお仕事を教えてください。',
   branches=[('ability-preparation','Nói khả năng và hỏi học việc','EXPERIENCE',['JOB_ABILITY','JOB_PREPARE'],'AVAILABILITY','Ứng viên cần làm rõ việc đã biết và cách chuẩn bị cho việc mới'),
             ('missing-application','Quên một phần hồ sơ','INTAKE',['JOB_DOCS','JOB_DOCS_CONFIRM'],'MOTIVATION','Ứng viên báo hồ sơ chưa đủ và muốn xác nhận cách bổ sung')],
   links=[('LEARNING','AVAILABILITY','Sau thảo luận học việc muốn chốt lịch có thể làm'),('AVAILABILITY','CONSTRAINT','Ca quán cần khác với lịch ứng viên')]),
 'service-complaint-return': dict(opening='商品のことで、どのようなご相談でしょうか。',
   branches=[('repair-recurrence','Vừa sửa nhưng lỗi tái diễn','INTAKE',['REPAIR_AGAIN','REPAIR_FOLLOW'],'SHARED_CONFIRM','Khách báo sản phẩm vừa sửa lại có lỗi'),
             ('return-conditions','Làm rõ điều kiện đổi hoặc hoàn','RESOLUTION',['RETURN_CONDITION','RETURN_CONFIRM'],'RESOLUTION','Khách cần kiểm tra điều kiện của phương án thay thế')],
   links=[('PROCESS','OPTIONS','Đã có bằng chứng mua nhưng hết hàng đổi'),('CHOOSE','REPAIR_AGAIN','Sau hỗ trợ, khách báo lỗi quay lại; chỉ mở khi khách báo sự cố mới')]),
 'project-deadline-negotiation': dict(opening='本日の進捗や課題について、まず概要を教えてください。',
   branches=[('resource-gap','Thiếu người ở khâu kiểm thử','REMAINING',['RESOURCE_GAP','RESOURCE_PLAN'],'DELIVERY','Người học báo nguồn lực không đủ để giữ tiến độ'),
             ('handoff-review','Phát sinh phản hồi sau bàn giao','INTAKE',['REVIEW_CHANGE','REVIEW_COMMIT'],'SHARED_CONFIRM','Đã bàn giao và nhận yêu cầu sửa; cần phân biệt lỗi với phạm vi mới')],
   links=[('PRIORITY','MITIGATION','Sau chốt phạm vi cần phương án giảm ảnh hưởng tiến độ'),('REMAINING','RISK','Việc còn lại có nguy cơ làm trễ hạn')]),
 'rental-contract-negotiation': dict(opening='お部屋や契約について、どのようなご相談でしょうか。',
   branches=[('room-search','Tìm phòng theo điều kiện','INTAKE',['ROOM_REQUIREMENTS','ROOM_COMPARE'],'SHARED_CONFIRM','Khách muốn tìm phòng, chưa phải gia hạn hợp đồng'),
             ('house-rules','Chưa rõ nội quy khi vào ở','INTAKE',['HOUSE_RULE','HOUSE_RULE_CHECK'],'SHARED_CONFIRM','Khách cần hướng dẫn sử dụng nhà hoặc cách tra cứu nội quy'),
             ('repair-recheck','Thiết bị vẫn lỗi sau sửa','COST_NOTICE',['RENT_RECHECK','RENT_NEXT'],'COST_NOTICE','Nhân viên đã xử lý nhưng khách báo thiết bị vẫn chưa hoạt động')],
   links=[('SETTLEMENT','FEES','Khách cần giải thích chi tiết các khoản quyết toán'),('RENT_NEXT','VISIT','Cần thống nhất một lần kiểm tra tiếp theo')]),
 'restaurant-order-and-pay': dict(opening='',
   branches=[('opening-hours','Sát giờ đóng cửa','GREETING',['RESTAURANT_HOURS','RESTAURANT_TIME'],'GREETING','Khách hỏi còn đủ thời gian gọi món trước khi ngồi'),
             ('menu-compare','So sánh vị trước khi chọn','ORDERING',['MENU_COMPARE','MENU_CHOOSE'],'CONFIRM','Khách muốn so sánh hai món trước khi chọn'),
             ('appearance-conditions','Món trông có vẻ cay','ORDERING',['MENU_APPEARANCE','MENU_CONDITION'],'ORDERING','Khách hỏi khả năng giảm cay trước khi chọn món')], links=[]),
}

def upsert(items, value, key='code'):
    for i, old in enumerate(items):
        if old[key] == value[key]:
            items[i] = value
            return
    items.append(value)

def append_prompt(state, instruction):
    state['prompt'] = state['prompt'].replace(instruction, '') + instruction

def edge(flow, source, target, label, required=(), entry=()):
    row=dict(source=source,target=target,label=label,requiredGoalCodes=list(required),entryGoalCodes=list(entry))
    for i,old in enumerate(flow['edges']):
        if (old['source'],old['target'])==(source,target): flow['edges'][i]=row; return
    flow['edges'].append(row)

def add_step(flow, code, title, evidence, prompt, reply, icon, col, row, required=False):
    upsert(flow['goals'],dict(code=code,title=title,description=evidence,type='MISSION',
        required=required,displayOrder=len(flow['goals'])+1,conditions=[dict(type='OBJECTIVE_FLAG',value=code)]))
    upsert(flow['states'],dict(code=code,name=title,summary=evidence,prompt=prompt,allowedGoalCodes=[code],
        suggestedReplies=[reply],icon='fa-solid fa-'+icon,column=col,row=row,transitionText=title))

def connect_legacy(group, spec):
    base=group['cases'][0]
    if base['flow'].get('version',1)>=2: return
    # Read the three authored cases before replacing the root flow.
    cases=copy.deepcopy(group['cases'])
    flow=dict(version=2,initialState='INTAKE',goals=[],states=[],edges=[],paths=[],
              terminalStateCodes=['END_SUCCESS'],hints=copy.deepcopy(base['flow']['hints']),
              endings=copy.deepcopy(base['flow']['endings']))
    add_step(flow,'INTAKE','Nêu việc muốn trao đổi','Nêu mục đích đến hoặc việc cần trao đổi với người đối thoại.',
        'Tiếp nhận yêu cầu để chọn tình huống phù hợp. Mở bằng: '+spec['opening']+
        ' Không hỏi lại chi tiết đã có trong lời khách. Khi đã biết mục đích, mở bước phù hợp với một câu hỏi tự nhiên.',
        cases[0]['flow']['states'][0]['suggestedReplies'][0],'comments',0,1,True)
    add_step(flow,'ISSUE_INTAKE','Làm rõ vấn đề đang gặp','Mô tả vấn đề hoặc điều cần thay đổi để người đối thoại hỗ trợ đúng.',
        'Làm rõ vấn đề khách đã nêu bằng một chi tiết cần thiết. Không bịa sự cố hay nhắc lại câu hỏi đã trả lời. '
        'Chọn hướng hỗ trợ đúng loại vấn đề dựa trên lịch sử, rồi mở bước tương ứng.',
        cases[1]['flow']['states'][0]['suggestedReplies'][0],'circle-question',1,3)
    add_step(flow,'SHARED_CONFIRM','Xác nhận kết quả và bước tiếp theo',
        'Nhắc lại hoặc đồng ý kết quả đã thống nhất, việc tiếp theo hoặc cách liên hệ; cảm ơn nếu phù hợp.',
        'Nhắc lại đúng kết quả của đường vừa đi: món đã mua, lịch đã chốt, hướng dẫn đã hiểu hoặc việc đang chờ kiểm tra. '
        'Kết quả có thể là chưa mua/chưa dùng và cần kiểm tra thêm. Không ép hoàn thành bước của nhánh chưa đi, '
        'không thu tiền/đặt lịch lại nếu đã xong. Nếu khách còn vấn đề thì theo cạnh tương ứng, chưa chào kết thúc.',
        'わかりました。ありがとうございます。','clipboard-check',10,1,True)
    flow['states'].append(dict(code='END_SUCCESS',name='Kết thúc cuộc trao đổi',summary='Đã xác nhận kết quả hoặc bước tiếp theo.',
        prompt='Chào kết thúc phù hợp kết quả vừa thống nhất; không giả định đã mua, đã dùng thuốc hoặc đã ký hợp đồng.',
        allowedGoalCodes=[],suggestedReplies=['ありがとうございました。'],icon='fa-solid fa-circle-check',column=11,row=1))
    edge(flow,'INTAKE','ISSUE_INTAKE','Khách nêu vấn đề hoặc muốn thay đổi',['INTAKE'],['ISSUE_INTAKE'])
    for i, case in enumerate(cases):
        route=case['flow']['states']
        row=1 if i==0 else 1+i*2
        col=1 if i==0 else 2
        for j,state in enumerate(route):
            state=copy.deepcopy(state);state.pop('next',None)
            state.update(column=col+j,row=row,summary=next(g['description'] for g in case['flow']['goals'] if g['code']==state['allowedGoalCodes'][0]),
                         icon=icon_for(group,state['code']),transitionText=state['name'])
            state['prompt'] += ' Giữ nguyên thông tin đã có trong lịch sử, không hỏi lại hay đặt lại các dữ kiện đã xác nhận.'
            upsert(flow['states'],state)
        for goal in case['flow']['goals']:
            goal=copy.deepcopy(goal); goal['required']=i==0; goal['displayOrder']=len(flow['goals'])+1
            upsert(flow['goals'],goal)
        source='INTAKE' if i==0 else 'ISSUE_INTAKE'
        edge(flow,source,route[0]['code'],case['title'],[source],route[0]['allowedGoalCodes'])
        for before,after in zip(route,route[1:]): edge(flow,before['code'],after['code'],'Đã rõ '+before['name'].lower(),before['allowedGoalCodes'],after['allowedGoalCodes'])
        edge(flow,route[-1]['code'],'SHARED_CONFIRM','Chốt kết quả đã trao đổi',route[-1]['allowedGoalCodes'],['SHARED_CONFIRM'])
        prefix=['INTAKE'] if i==0 else ['INTAKE','ISSUE_INTAKE']
        flow['paths'].append(dict(code=case['code'],title=case['title'],stateCodes=prefix+[s['code'] for s in route]+['SHARED_CONFIRM','END_SUCCESS']))
    edge(flow,'SHARED_CONFIRM','END_SUCCESS','Đã xác nhận kết quả và bước tiếp theo',['SHARED_CONFIRM'])
    base['flow']=flow
    base['title']=group['title']
    base['background']=f"Bạn trao đổi với {group['ai_role']['name'].lower()} trong bối cảnh {group['location'].lower()}. Có thể thực hiện việc thông thường hoặc nêu vấn đề để cùng tìm cách xử lý. Chỉ cần hoàn thành những bước thuộc tình huống bạn gặp."
    base['learning']['branchDialogues']=[dict(code=c['code'],title=c['title'],exampleDialogue=c['learning']['exampleDialogue']) for c in cases[1:]]

def icon_for(group, code):
    # Extract the repository's existing semantic icons, so transferred goals keep theirs.
    import re
    text=(ROOT/'src/main/java/com/jlas/kaiwa/util/ScenarioIcons.java').read_text(encoding='utf-8')
    section=text.split('Map.entry("'+group['slug']+'",',1)[1].split(')),',1)[0]
    found=re.search('"'+re.escape(code)+'", "([a-z-]+)"',section)
    return 'fa-solid fa-'+(found[1] if found else 'comments')

def main():
    catalog=json.loads(CATALOG.read_text(encoding='utf-8'))
    steps={}
    for line in STEPS.strip().splitlines():
        cells=line.split('|')
        if len(cells)!=8: raise ValueError('Invalid authored step: '+line)
        steps[cells[0]]=cells
    for group in catalog['scenarios']:
        spec=SPECS[group['slug']]
        connect_legacy(group,spec)
        case=group['cases'][0];flow=case['flow'];states={s['code']:s for s in flow['states']}
        for branch_index,(code,title,attach,codes,join,condition) in enumerate(spec['branches']):
            row=branch_index*2
            col=states[attach]['column']+1 if group['slug']!='restaurant-order-and-pay' else 3
            dialogue=[]
            for index,step_code in enumerate(codes):
                step_code,name,evidence,ai,vi,reply,translation,icon=steps[step_code]
                prompt=(f'Người học đang ở nhánh: {title}. {condition}. Mở bằng ý: {ai} '
                    f'Mục tiêu người học: {evidence} Chấp nhận cách nói tương đương và ghi nhận thông tin đã rõ. '
                    'Chỉ hỏi một ý mỗi lượt. Giữ tên, ngày giờ, sản phẩm, nhu cầu và điều kiện đã thống nhất trong lịch sử. '
                    'Không tự làm việc thay khách và không tự tạo sự cố tiếp theo. ')
                if group['slug']=='pharmacy-cold-medicine':
                    prompt+='Chỉ luyện hỏi/đối chiếu hướng dẫn. Không tự bịa liều, mức an toàn phối hợp hay hiệu quả thuốc. '
                if step_code.startswith('LEAVE_'):
                    prompt+='Giữ vai AI của kịch bản. Nếu đang là đồng nghiệp, chỉ hỗ trợ chuyển yêu cầu đến người phụ trách và xác nhận bàn giao; không tự duyệt nghỉ hay giả làm người duyệt. '
                add_step(flow,step_code,name,evidence,prompt,reply,icon,col+index,row)
                dialogue += [dict(speaker='ai',text=ai,translation=vi),dict(speaker='learner',text=reply,translation=translation)]
            # Existing scene goals remain pending until the learner returns to it.
            gate=states[attach]['allowedGoalCodes'] if attach!=join else []
            if code=='menu-compare':
                gate=[]
                next(s for s in flow['states'] if s['code']=='MENU_CHOOSE')['allowedGoalCodes']=['MENU_CHOOSE','ORDER']
            edge(flow,attach,codes[0],condition,gate,[codes[0]])
            for a,b in zip(codes,codes[1:]): edge(flow,a,b,'Đã rõ '+steps[a][1].lower(),[a],[b])
            join_goals=states[join]['allowedGoalCodes']
            required=[codes[-1],'ORDER'] if code=='menu-compare' else [codes[-1]]
            edge(flow,codes[-1],join,'Đã thống nhất, tiếp tục bước chung',required,join_goals)
            upsert(flow['paths'],dict(code=code,title=title,stateCodes=[attach,*codes,join]))
            upsert(case['learning'].setdefault('branchDialogues',[]),dict(code=code,title=title,exampleDialogue=dialogue))
            states={s['code']:s for s in flow['states']}
        for source,target,label in spec['links']:
            # A branch may add a new question after the current goal is complete.
            edge(flow,source,target,label,states[source]['allowedGoalCodes'],states[target]['allowedGoalCodes'])
        if group['slug']=='convenience-store-checkout':
            edge(flow,'ASK_CHOPSTICKS','SHARED_CONFIRM','Đã nhận dụng cụ và thanh toán',['ASK_CHOPSTICKS','BAG_PAY'])
            append_prompt(states['BAG_PAYMENT'],' Nếu túi đã được đồng ý/từ chối thì giữ lựa chọn đó, chỉ xác nhận thanh toán; nếu thẻ lỗi thì đi RETRY, chưa ghi BAG_PAY hoàn thành.')
            edge(flow,'BAG_PAYMENT','RETRY','Thẻ thanh toán thất bại, cần cách khác',[],['RETRY'])
            states['FINISH_PAY']['allowedGoalCodes']=['FINISH_PAY','BAG_PAY']
            append_prompt(states['FINISH_PAY'],' Ghi nhận BAG_PAY khi người học đã chọn cách trả tiền và hoàn thành thanh toán cho đơn hiện tại.')
            next(g for g in flow['goals'] if g['code']=='BAG_PAY')['description']='Chọn cách trả tiền, xác nhận thanh toán và trả lời về túi nếu nhân viên đã hỏi; nếu lựa chọn túi đã rõ thì không cần nói lại.'
            edge(flow,'FINISH_PAY','SHARED_CONFIRM','Đã trả tiền và xác nhận đơn',['FINISH_PAY','BAG_PAY'],['SHARED_CONFIRM'])
            for path in flow['paths']:
                if path['stateCodes'][-3:]==['ASK_CHOPSTICKS','SHARED_CONFIRM','END_SUCCESS']:
                    path['stateCodes'].insert(-2,'BAG_PAYMENT')
        if group['slug']=='restaurant-order-and-pay':
            flow['edges']=[e for e in flow['edges'] if (e['source'],e['target'])!=('RESTAURANT_TIME','SEATING')]
            append_prompt(states['MENU_CHOOSE'],' Khi khách chọn rõ món và số lượng, ghi cả MENU_CHOOSE và ORDER; không bắt gọi lại món đã chọn.')
        if group['slug']=='pharmacy-cold-medicine':
            append_prompt(states['DISCLOSE'],' Nếu chưa nhớ tên hoặc nhãn thuốc thì ghi nhận đã báo thiếu thông tin, chưa khẳng định dùng chung được; có thể đi MED_UNCERTAIN để kiểm tra tiếp.')
            next(g for g in flow['goals'] if g['code']=='DISCLOSE')['description']='Nói đang dùng thuốc khác và cung cấp tên/nhãn nếu biết; nếu chưa biết thì nêu rõ để kiểm tra thêm, không giả vờ biết.'
        if group['slug']=='rental-contract-negotiation':
            states['FEES']['prompt']='Giải thích khoản phí khách đang hỏi theo ngữ cảnh: gia hạn, sửa chữa hoặc quyết toán trả nhà. Chỉ dùng số liệu đã có trong hội thoại/văn bản, không bịa quy định hợp đồng. Hỏi khách muốn làm rõ mục nào, rồi xác nhận đã hiểu hoặc cần văn bản.'
        flow['expansionVersion']=1
        if group['slug']!='restaurant-order-and-pay':
            states['INTAKE']['row']=2;states['SHARED_CONFIRM']['row']=2;states['END_SUCCESS']['row']=2
        if group['slug']=='hotel-check-in':
            next(g for g in flow['goals'] if g['code']=='INFO')['description']='Hỏi hoặc xác nhận thông tin dịch vụ cần dùng, như Wi-Fi, hành lý hoặc giờ ăn sáng.'
            append_prompt(states['INFO'],' Nếu dịch vụ vừa được kiểm tra ở nhánh khác, ghi nhận xác nhận kết quả; không bắt khách hỏi lại thông tin đã rõ.')
        # Stable display order on repeat authoring.
        for index,goal in enumerate(flow['goals']): goal['displayOrder']=index+1
        flow['skillTransfers'] = []
        lessons=json.loads((ROOT/'data/ai-call/minna-skill-map.json').read_text(encoding='utf-8'))['lessons']
        for lesson in lessons:
            if group['slug'] not in lesson['targets']: continue
            codes=lesson['targets'][group['slug']]
            if not set(codes) <= set(states): raise ValueError(f"Missing skill state: {group['slug']} {codes}")
            flow['skillTransfers'].append(dict(lessons=[lesson['lesson']],stateCodes=codes,
                explanation=lesson['explanation'],pattern=lesson['pattern'],example=lesson['example'],translation=lesson['translation']))
            if not any(g['pattern']==lesson['pattern'] for g in group['learning']['grammar']):
                group['learning']['grammar'].append(dict(pattern=lesson['pattern'],explanation=lesson['explanation'],example=lesson['example']))
    CATALOG.write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Expanded all 14 existing roots; retained 28 legacy child cases.')

if __name__=='__main__': main()
