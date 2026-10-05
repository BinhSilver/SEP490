"""Finish every existing branch with an authored outcome, not a generic stop.

Run after graph expansion, before author_progression.py and generate_seed.py.
Keeps old edges for active route snapshots; new authored routes require follow-through.
All examples are original role-play material. No new scenario/database rows.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# code | title | learner evidence | AI | translation | learner | translation | icon
STEPS = {
 'restaurant-order-and-pay': '''
STORY_MEAL|Dùng bữa và kiểm tra lần cuối|Xác nhận đã dùng xong món đúng yêu cầu, rồi xin thanh toán; nếu món chưa đúng thì nêu vấn đề để xử lý trước.|お食事はお済みですか。|Quý khách dùng bữa xong chưa ạ?|はい。お会計をお願いします。|Vâng. Cho tôi thanh toán.|utensils
STORY_RECEIPT|Nhận tiền thừa và kiểm tra hóa đơn|Kiểm tra món, số tiền và tiền thừa theo giao dịch đã nói; nhận hóa đơn hoặc từ chối lịch sự nếu không cần.|お会計はこちらです。レシートはご入用ですか。|Đây là hóa đơn. Quý khách có cần biên lai không?|はい、お願いします。金額も確認できました。|Vâng, cho tôi biên lai. Tôi đã kiểm tra số tiền.|receipt
STORY_LEAVE|Chào và rời quán|Xác nhận đã nhận đủ đồ của mình, cảm ơn bữa ăn và chào trước khi rời quán.|お忘れ物はありませんか。|Quý khách có để quên gì không?|大丈夫です。ごちそうさまでした。|Không ạ. Cảm ơn về bữa ăn.|door-open
''',
 'first-meeting-introduction': '''
STORY_MEET_AGAIN|Chốt cách gặp lại|Nhắc lại giờ, nơi hoặc cách gặp lại đã đồng ý; có thể từ chối trao đổi liên hệ và chọn hẹn tại địa điểm chung.|次に会う場所も決めておきましょうか。|Chúng ta chốt cả nơi gặp lần tới nhé?|では、約束の日に駅の入口で会いましょう。|Vậy ngày đã hẹn, gặp nhau ở lối vào ga nhé.|calendar-check
STORY_GOODBYE|Chào kết thúc buổi làm quen|Cảm ơn cuộc trò chuyện và chào; giữ đúng cuộc hẹn hoặc lựa chọn không hẹn đã thống nhất.|今日はお話しできてよかったです。|Hôm nay được trò chuyện với bạn thật vui.|私もです。では、また。|Tôi cũng vậy. Hẹn gặp lại.|handshake
''',
 'station-directions-and-transfer': '''
STORY_GATE|Qua cổng và kiểm tra tàu sẽ lên|Sau khi mua vé hoặc dùng vé hợp lệ, nhắc lại đúng sân ga, hướng tàu và chuyến đã chọn trước khi lên.|乗る前に、行き先とホームを確認しましょう。|Trước khi lên, hãy kiểm tra điểm đến và sân ga.|教えていただいたホームから、渋谷方面の電車に乗ります。|Tôi sẽ lên tàu hướng Shibuya ở sân ga vừa được hướng dẫn.|train-subway
STORY_ARRIVAL|Đến ga đích và hỏi lối ra|Chuyển cảnh sau chuyến đi: xác nhận đã tới đúng ga, hỏi lối ra cần dùng với nhân viên tại ga đích.|渋谷駅に着きました。出口についてお手伝いしましょうか。|Đã tới ga Shibuya. Quý khách cần hỏi lối ra không?|駅の外へ出たいです。出口はどちらですか。|Tôi muốn ra ngoài ga. Lối ra ở phía nào?|location-dot
STORY_EXIT|Ra khỏi ga và kết thúc hành trình|Nhắc lại lối ra vừa được hướng dẫn và cảm ơn; không coi việc hỏi tuyến ban đầu là đã tới nơi.|こちらの案内に沿って進むと出口です。|Đi theo hướng dẫn này sẽ tới lối ra.|わかりました。ありがとうございました。|Tôi hiểu rồi. Cảm ơn ạ.|door-open
''',
 'convenience-store-checkout': '''
STORY_ITEMS|Kiểm tra hàng, dụng cụ và tiền thừa|Sau thanh toán, kiểm tra đúng sản phẩm, số lượng, hâm nóng và dụng cụ theo yêu cầu; nếu thiếu thì yêu cầu bổ sung rồi kiểm tra lại.|商品とお箸をご確認ください。|Quý khách kiểm tra hàng và đũa giúp tôi.|はい、注文した数で合っています。ありがとうございます。|Vâng, đúng số lượng tôi mua. Cảm ơn.|bag-shopping
STORY_TAKE|Nhận đủ đồ và rời cửa hàng|Xác nhận đã nhận hàng, tiền thừa hoặc biên lai theo giao dịch và chào rời cửa hàng.|お忘れ物のないようにお気をつけください。|Quý khách nhớ kiểm tra đồ trước khi đi.|全部受け取りました。ありがとうございました。|Tôi đã nhận đủ. Cảm ơn ạ.|door-open
''',
 'clothes-shopping-size-fitting': '''
STORY_TRANSACTION|Thực hiện quyết định mua hoặc đổi|Thực hiện quyết định đã nói: mua thì xác nhận giá và thanh toán; đổi thì nhận đúng cỡ theo điều kiện đã kiểm tra; không mua thì trả đồ và từ chối lịch sự.|先ほど決めたお品物でお手続きを進めてよろしいですか。|Tôi thực hiện thủ tục với sản phẩm vừa chọn nhé?|はい。このサイズでお願いします。お支払い方法も確認したいです。|Vâng, lấy cỡ này giúp tôi. Tôi cũng muốn xác nhận cách thanh toán.|cash-register
STORY_CLOTHES_CHECK|Kiểm tra đồ nhận được và kết thúc|Kiểm tra đúng mẫu và cỡ khi mua hoặc đổi; nếu đã quyết định chưa mua thì xác nhận không có giao dịch, trả đồ thử và chào.|サイズとお品物に間違いはありませんか。|Cỡ và sản phẩm có đúng không ạ?|はい、合っています。ありがとうございました。|Vâng, đúng rồi. Cảm ơn ạ.|shirt
''',
 'clinic-appointment': '''
STORY_BOOKING_RECORD|Nhận xác nhận lịch đã được ghi nhận|Kiểm tra tên, ngày, giờ và cách nhận xác nhận đặt lịch; khi đổi lịch, xác nhận lịch cũ đã được thay thế, không chỉ đề xuất giờ mới.|お名前と予約日時を確認してから、ご予約の案内をお渡しします。|Tôi kiểm tra tên và lịch hẹn rồi gửi xác nhận đặt lịch.|はい。名前と日時は合っています。予約の案内をお願いします。|Vâng, tên và lịch đúng rồi. Cho tôi xác nhận đặt lịch.|calendar-check
STORY_CLINIC_PREPARE|Chốt việc chuẩn bị và kết thúc cuộc gọi|Nhắc lại giấy tờ theo hướng dẫn của lễ tân và việc còn phải bổ sung; ghi nhận nơi, giờ đến và cách liên hệ nếu cần đổi lịch.|当日はご案内した書類をお持ちください。不明な点はお電話ください。|Hôm đó vui lòng mang giấy tờ đã được hướng dẫn. Có gì chưa rõ thì gọi lại.|案内を確認して準備します。ありがとうございました。|Tôi sẽ kiểm tra hướng dẫn và chuẩn bị. Cảm ơn ạ.|clipboard-check
''',
 'pharmacy-cold-medicine': '''
STORY_PHARMACY_CHECK|Thực hiện bước kiểm tra với dược sĩ|Cung cấp nhãn hoặc thông tin còn thiếu và nhờ dược sĩ kiểm tra; chưa có đủ thông tin thì thống nhất chưa tự dùng hoặc phối hợp thuốc. Không tự suy ra liều hay độ an toàn.|まず、ラベルとお使いの薬の情報を薬剤師と一緒に確認しましょう。|Trước hết, hãy cùng dược sĩ kiểm tra nhãn và thông tin thuốc đang dùng.|はい。確認できるまで、自分で一緒には飲みません。|Vâng. Khi chưa kiểm tra xong, tôi không tự uống cùng.|prescription-bottle-medical
STORY_PHARMACY_PLAN|Chốt việc mua hoặc bổ sung thông tin|Nếu dược sĩ đã kiểm tra và khách quyết định mua thì xác nhận thanh toán và tài liệu hướng dẫn; nếu chưa đủ thông tin thì chốt việc mang nhãn hoặc liên hệ lại, không ép mua.|確認結果に合わせて、購入するか、情報をそろえて再度相談するか決めましょう。|Dựa vào kết quả kiểm tra, hãy chọn mua hoặc bổ sung thông tin để hỏi lại.|薬の情報をそろえて、もう一度相談します。ありがとうございました。|Tôi sẽ bổ sung thông tin thuốc rồi hỏi lại. Cảm ơn ạ.|clipboard-check
''',
 'office-reschedule-meeting': '''
STORY_MEETING_UPDATE|Gửi cập nhật hoặc lời nhắn đã thống nhất|Gửi đúng giờ, nơi hoặc đường dẫn mới cho người liên quan; nếu đang xin phép rời sớm thì gửi yêu cầu tới người có quyền duyệt, chưa coi đồng nghiệp đồng ý là được phép.|変更内容を関係者に送って、確認をお願いしましょう。|Hãy gửi thay đổi cho người liên quan và nhờ xác nhận.|決めた時間と場所を送ります。確認の返事もお願いします。|Tôi sẽ gửi giờ và nơi đã chốt. Nhờ mọi người trả lời xác nhận.|paper-plane
STORY_MEETING_REPLY|Nhận phản hồi và kiểm tra bước thực hiện|Chuyển cảnh khi có phản hồi: nhắc lại nội dung được xác nhận, kiểm tra đường dẫn hoặc người nhận bàn giao. Chưa được duyệt thì hỏi tiếp, không tự rời đi.|返信を確認しましょう。予定と引き継ぎ先に間違いはありませんか。|Hãy kiểm tra phản hồi. Lịch và người nhận bàn giao có đúng không?|返事を確認しました。決まった内容で準備します。|Tôi đã kiểm tra phản hồi. Tôi sẽ chuẩn bị theo nội dung đã thống nhất.|envelope-open-text
''',
 'school-absence-and-homework': '''
STORY_STUDY_PLAN|Thực hiện phần học bù đã thống nhất|Chuyển cảnh sang buổi học bù: nhận bài hoặc tài liệu, thử phần được hướng dẫn và nói rõ điều còn chưa hiểu; không coi việc xin bài là đã học xong.|では、案内した部分を実際にやってみましょう。わからないところはありますか。|Hãy thử làm phần đã được hướng dẫn. Có chỗ nào chưa hiểu không?|説明を読んでやってみました。この部分も確認していいですか。|Tôi đã đọc giải thích và thử làm. Tôi hỏi lại phần này được không?|book-open
STORY_SUBMISSION|Nộp bài hoặc báo phần còn lại|Nêu phần đã làm, nộp theo cách đã thống nhất; nếu chưa xong thì báo phần còn lại và mốc được giáo viên đồng ý, không tự khẳng định đã nộp đủ.|できた分を提出してください。残っている分も教えてください。|Hãy nộp phần đã làm và cho biết phần còn lại.|できた分を提出しました。残りは約束した日までに出します。|Tôi đã nộp phần làm xong. Phần còn lại sẽ nộp đúng ngày đã hẹn.|file-arrow-up
STORY_TEACHER_REPLY|Nhận xác nhận của giáo viên|Kiểm tra giáo viên đã nhận phần nộp và lịch học hoặc hạn còn lại; nhắc lại cam kết rồi cảm ơn.|提出分を受け取りました。残りの期限も確認しましょう。|Tôi đã nhận phần nộp. Chúng ta kiểm tra cả hạn phần còn lại nhé.|はい。その期限で進めます。ありがとうございました。|Vâng, tôi sẽ làm theo hạn đó. Cảm ơn.|circle-check
''',
 'hotel-check-in': '''
STORY_HOTEL_VERIFY|Nhận và kiểm tra kết quả hỗ trợ|Chuyển cảnh sau hỗ trợ: kiểm tra chìa khóa và phòng, thử lại Wi-Fi, nhận hành lý hoặc xác minh đồ để quên đúng với tình huống. Còn lỗi thì nêu lỗi để xử lý, không đánh dấu đã ổn.|対応した内容をご確認ください。まだお困りの点はありますか。|Quý khách kiểm tra phần đã được hỗ trợ. Có còn vấn đề nào không?|実際に確認しました。今は問題ありません。|Tôi đã kiểm tra thực tế. Bây giờ không còn vấn đề.|clipboard-check
STORY_HOTEL_CLOSE|Chốt việc lưu trú hoặc nhận đồ|Nhắc lại việc đã xử lý và thông tin cần cho bước tiếp theo; đồ thất lạc chưa tìm được thì chốt người liên hệ và thời điểm báo lại, không tự coi đã nhận đồ.|次のご案内も確認しておきましょう。|Chúng ta kiểm tra cả thông tin cho bước tiếp theo nhé.|受け取るものと次の案内を確認できました。ありがとうございます。|Tôi đã kiểm tra đồ cần nhận và hướng dẫn tiếp theo. Cảm ơn.|bell-concierge
''',
 'part-time-job-interview': '''
STORY_APPLICATION|Hoàn tất hồ sơ và bước sau phỏng vấn|Nộp phần hồ sơ đã có hoặc bổ sung đúng cách và hạn đã thống nhất; hỏi bước xét tuyển hoặc buổi học việc nếu thực sự được đề nghị, không tự coi đã trúng tuyển.|必要な書類と、面接後の流れを確認しましょう。|Chúng ta kiểm tra giấy tờ cần thiết và quy trình sau phỏng vấn nhé.|書類をそろえて提出します。結果の連絡方法も教えてください。|Tôi sẽ chuẩn bị đủ giấy tờ và nộp. Xin cho biết cách thông báo kết quả.|file-circle-check
STORY_RECRUITMENT_REPLY|Xác nhận hồ sơ được nhận và cách báo kết quả|Chuyển cảnh sau khi gửi: xác nhận đã nhận hồ sơ hoặc phần còn thiếu; nhắc lại cách và thời điểm dự kiến nhận thông tin tiếp theo, không bảo đảm được tuyển.|提出内容を確認しました。結果はご案内した方法でお知らせします。|Chúng tôi đã kiểm tra hồ sơ nộp. Kết quả sẽ được báo theo cách đã hướng dẫn.|受け取りの確認ができました。ご連絡をお待ちしています。|Tôi đã xác nhận hồ sơ được nhận. Tôi sẽ chờ thông báo.|envelope-open-text
''',
 'service-complaint-return': '''
STORY_RETURN_HANDOVER|Giao hàng và nhận chứng từ xử lý|Thực hiện phương án cửa hàng đã xác nhận: giao sản phẩm, nhận phiếu đổi, tiếp nhận sửa hoặc xác nhận yêu cầu hoàn tiền; kiểm tra mã và điều kiện, không mặc định mọi yêu cầu được chấp nhận.|確認した方法で受け付けます。控えの内容もご確認ください。|Chúng tôi tiếp nhận theo phương án đã xác nhận. Quý khách kiểm tra thông tin trên phiếu.|商品をお渡しします。受付内容と連絡先も確認します。|Tôi giao sản phẩm. Tôi sẽ kiểm tra nội dung tiếp nhận và thông tin liên hệ.|receipt
STORY_RETURN_RESULT|Nhận kết quả đổi, sửa hoặc hoàn|Chuyển cảnh tới kết quả được cửa hàng thông báo: kiểm tra hàng đổi, thử hàng sửa hoặc xác nhận khoản hoàn thực nhận. Nếu đang chờ thì làm rõ mốc và cách báo, không nói đã xong.|その後の対応結果をご確認ください。|Quý khách kiểm tra kết quả xử lý sau đó.|結果を確認しました。受け取った品物と内容も確認します。|Tôi đã kiểm tra kết quả. Tôi cũng sẽ kiểm tra sản phẩm nhận được và nội dung xử lý.|box-open
STORY_RETURN_CLOSE|Xác nhận việc đã giải quyết và kết thúc|Chỉ xác nhận hoàn tất khi đã kiểm tra kết quả đúng; còn lỗi hoặc thiếu khoản thì nêu cụ thể để cửa hàng xử lý tiếp ngay ở bước này.|内容はご希望の対応と合っていますか。|Kết quả có đúng với phương án quý khách đã chọn không?|はい、確認できました。ご対応ありがとうございました。|Vâng, tôi đã kiểm tra. Cảm ơn cửa hàng đã xử lý.|circle-check
''',
 'project-deadline-negotiation': '''
STORY_HANDOFF|Bàn giao phần việc đã cam kết|Chuyển cảnh đến mốc đã thống nhất: báo phần thực sự hoàn thành, gửi tài liệu hoặc phiên bản và nêu giới hạn còn lại; không mặc định mọi việc đã xong khi mới xin đổi hạn.|約束した更新の時点です。何を共有できますか。|Đã tới mốc cập nhật đã hẹn. Bạn có thể gửi phần nào?|合意した部分を共有しました。残っている範囲も一覧にしました。|Tôi đã gửi phần đã thống nhất. Tôi cũng đã liệt kê phần còn lại.|file-arrow-up
STORY_REVIEW_ACK|Nhận phản hồi bàn giao và chốt việc còn lại|Xác nhận người phụ trách nhận đúng tài liệu, làm rõ phản hồi và người xử lý cùng mốc tiếp theo nếu còn việc; không tự coi người nhận đã duyệt toàn bộ.|共有内容を受け取りました。次に確認する項目を整理しましょう。|Tôi đã nhận nội dung bàn giao. Chúng ta xác định các mục cần kiểm tra tiếp nhé.|受け取りを確認できました。残りの担当と期限も確認します。|Tôi đã xác nhận phần bàn giao được nhận. Tôi sẽ kiểm tra cả người phụ trách và hạn phần còn lại.|clipboard-check
''',
 'rental-contract-negotiation': '''
STORY_RENT_ACTION|Thực hiện việc đã thống nhất với quản lý|Chuyển cảnh phù hợp: nhận văn bản gia hạn, tiếp người kiểm tra, dự buổi xem phòng, trả chìa khóa hoặc lấy hướng dẫn nội quy; chỉ làm sau khi các điều kiện đã được xác nhận.|決めた手続きに進みましょう。内容をご確認ください。|Chúng ta thực hiện thủ tục đã thống nhất. Xin kiểm tra nội dung.|案内された手続きを進めます。不明な費用は先に確認します。|Tôi sẽ làm theo thủ tục được hướng dẫn. Phí chưa rõ thì tôi kiểm tra trước.|file-signature
STORY_RENT_CHECK|Kiểm tra kết quả và giấy tờ liên quan|Kiểm tra thiết bị sau sửa, phòng đã xem, biên nhận chìa khóa hoặc văn bản đúng với tình huống; còn lỗi hoặc chưa rõ điều khoản thì yêu cầu kiểm tra tiếp, không tự coi đã ký hay nhận lại cọc.|対応後の状態と、お渡しした書類をご確認ください。|Xin kiểm tra tình trạng sau xử lý và giấy tờ đã nhận.|状態と書類を確認しました。残っている項目も確認したいです。|Tôi đã kiểm tra tình trạng và giấy tờ. Tôi muốn xác nhận cả mục còn lại.|clipboard-check
STORY_RENT_CLOSE|Chốt việc đã hoàn tất và người liên hệ|Nhắc lại kết quả hoặc phần còn phải làm, người phụ trách và thời điểm nhận thông báo bằng văn bản; không tự bịa quy định hay chi phí.|残りの連絡先と、次のご案内を確認しましょう。|Chúng ta kiểm tra người liên hệ và hướng dẫn tiếp theo nhé.|連絡先と次の手順を確認できました。ありがとうございました。|Tôi đã kiểm tra người liên hệ và bước tiếp theo. Cảm ơn.|phone
'''
}

OTHER_RESTAURANT = '''
STORY_DECLINE|Từ chối món hoặc thời gian chờ|Nói rõ không chọn phương án ở quán hiện tại; kiểm tra đơn chưa phục vụ đã hủy và khoản phải trả nếu có, không bỏ đi khi chưa xử lý nghĩa vụ thanh toán.|今回はご注文を取りやめますか。未提供の注文とお会計も確認します。|Lần này quý khách muốn hủy yêu cầu phải không? Tôi sẽ kiểm tra đơn chưa phục vụ và khoản thanh toán.|はい、取りやめます。支払いが必要なものはありますか。|Vâng, tôi xin hủy. Có khoản nào cần thanh toán không?|circle-xmark
STORY_OTHER_SHOP|Hỏi quán khác phù hợp|Hỏi quán gần đó còn phục vụ món hoặc còn chỗ, kiểm tra vị trí và điều kiện; đây là địa điểm giả định trong hội thoại.|近くに別のお店があります。どんなお店をお探しですか。|Gần đây có quán khác. Quý khách muốn tìm quán thế nào?|今から食事ができるお店はありますか。行き方も教えてください。|Có quán nào bây giờ dùng bữa được không? Xin chỉ đường giúp.|map-location-dot
STORY_FIRST_GOODBYE|Chốt việc ở quán đầu rồi rời đi|Xác nhận quán đầu đã hủy đúng đơn, xử lý khoản phải trả nếu có và cảm ơn chỉ dẫn trước khi đi.|こちらでの手続きは終わりました。道順はおわかりになりましたか。|Thủ tục ở quán này đã xong. Quý khách đã rõ đường đi chưa?|はい、確認できました。ありがとうございました。|Vâng, tôi đã xác nhận. Cảm ơn ạ.|door-open
STORY_SECOND_VISIT|Tới quán khác và hỏi bàn|Chuyển cảnh rõ ràng sang quán thứ hai; AI trở thành nhân viên quán mới cùng vai phục vụ, không giả vờ nhân viên quán đầu đi cùng khách. Báo số người và hỏi còn bàn trước khi gọi món.|場面が変わり、別のお店に着きました。いらっしゃいませ。何名様ですか。|Chuyển cảnh: đã tới quán khác. Xin chào, quý khách đi mấy người ạ?|一人です。今、食事できますか。|Một người ạ. Bây giờ dùng bữa được không?|shop
'''

FINISH_TITLES = {
 'restaurant-order-and-pay':'Đã dùng bữa và rời quán',
 'first-meeting-introduction':'Đã làm quen và chào tạm biệt',
 'station-directions-and-transfer':'Đã tới ga đích và tìm lối ra',
 'convenience-store-checkout':'Đã nhận đủ hàng và rời cửa hàng',
 'clothes-shopping-size-fitting':'Đã hoàn tất việc chọn hoặc đổi đồ',
 'clinic-appointment':'Đã chốt lịch và việc chuẩn bị',
 'pharmacy-cold-medicine':'Đã chốt việc mua hoặc kiểm tra thêm',
 'office-reschedule-meeting':'Đã nhận xác nhận lịch hoặc bàn giao',
 'school-absence-and-homework':'Đã học bù và xác nhận phần nộp',
 'hotel-check-in':'Đã nhận kết quả hỗ trợ từ khách sạn',
 'part-time-job-interview':'Đã hoàn tất hồ sơ và cách nhận kết quả',
 'service-complaint-return':'Đã nhận và kiểm tra kết quả xử lý',
 'project-deadline-negotiation':'Đã bàn giao và chốt phần còn lại',
 'rental-contract-negotiation':'Đã kiểm tra kết quả và chốt thủ tục',
}

def rows(text):
    return [line.split('|') for line in text.strip().splitlines()]

def upsert(items, value):
    index = next((i for i, old in enumerate(items) if old['code'] == value['code']), None)
    if index is None: items.append(value)
    else: items[index] = value

def add_state(flow, row, column, visual_row=0):
    code, title, evidence, ai, ai_vi, learner, learner_vi, icon = row
    order=next((g['displayOrder'] for g in flow['goals'] if g['code']==code),100+len(flow['goals']))
    upsert(flow['goals'], dict(code=code, title=title, description=evidence, type='MISSION',
        displayOrder=order, required=False,
        conditions=[dict(type='OBJECTIVE_FLAG', value=code)]))
    upsert(flow['states'], dict(code=code, name=title, summary=evidence,
        prompt=f'{evidence} AI mở bước bằng ý: {ai} Chỉ ghi mục tiêu khi người học thể hiện bằng lời. '
               'Giữ đúng lựa chọn, thời gian, món, giấy tờ và điều kiện đã có trong lịch sử. '
               'Nếu còn vướng mắc thì làm rõ và xử lý ở bước này, chưa tự đánh dấu hoàn tất. '
               'Chuyển cảnh chỉ khi bước trước đã được hai bên xác nhận; nêu rõ khoảng thời gian đã trôi qua nếu có.',
        allowedGoalCodes=[code], suggestedReplies=[learner], column=column, row=visual_row,
        icon='fa-solid fa-'+icon, transitionText=title))

def connect(flow, source, target):
    states={s['code']:s for s in flow['states']}
    if any(e['source']==source and e['target']==target for e in flow['edges']): return
    flow['edges'].append(dict(source=source, target=target,
        label=states[target]['name'], requiredGoalCodes=states[source]['allowedGoalCodes'], entryGoalCodes=[]))

def sample(row):
    return [dict(speaker='ai', text=row[3], translation=row[4]),
            dict(speaker='learner', text=row[5], translation=row[6])]

def main():
    file=ROOT/'data/ai-call/catalog.json'
    catalog=json.loads(file.read_text(encoding='utf-8'))
    total=0; previously_short=0
    for group in catalog['scenarios']:
        case=group['cases'][0]; flow=case['flow']; slug=group['slug']
        old_stages={p['code']:p for p in flow['stages']}
        endings=rows(STEPS[slug]); closure=[r[0] for r in endings]
        for index,row in enumerate(endings): add_state(flow, row, 20+index)
        restaurant=slug=='restaurant-order-and-pay'
        if restaurant:
            for index,row in enumerate(rows(OTHER_RESTAURANT)): add_state(flow, row, 6+index, 8)
            for code,title,source,prerequisite in [
                ('sold-out-other-restaurant','Hết món: sang quán khác và dùng bữa','SOLD_OUT','sold-out'),
                ('crowded-other-restaurant','Quán đông: sang quán khác và dùng bữa','WAIT_CHOICE','crowded'),
                ('closing-other-restaurant','Quán sắp đóng: sang quán khác và dùng bữa','RESTAURANT_TIME','opening-hours')]:
                if code not in old_stages:
                    from author_progression import route
                    prefix=route(flow,flow['initialState'],source)
                    full=prefix+[r[0] for r in rows(OTHER_RESTAURANT)]+['SEATING','ORDERING','CONFIRM','SERVED','BILL','PAYMENT','END_SUCCESS']
                    flow['paths'].append(dict(code=code,title=title,stateCodes=full))
                    old_stages[code]=dict(stateCodes=full)
        samples={p['code']:p for p in case['learning'].get('branchDialogues',[])}
        for path in flow['paths']:
            previously_short+=path['stateCodes'][-1] not in flow['terminalStateCodes']
            full=list(old_stages[path['code']]['stateCodes'])
            # Idempotent refresh: strip only our previously authored closing nodes.
            full=[c for c in full if c not in closure]
            if restaurant:
                bill=full.index('BILL');full.insert(bill,closure[0]);full[-1:-1]=closure[1:]
            else: full[-1:-1]=closure
            for source,target in zip(full,full[1:]): connect(flow,source,target)
            path['stateCodes']=full
            for stage in flow['stages']:
                if stage['code']==path['code']: stage['stateCodes']=full
            existing=samples.get(path['code'])
            if existing:
                dialogue=list(existing.get('openingDialogue',existing['exampleDialogue']))
            else:
                child=next((c for c in group['cases'][1:] if c['code']==path['code']),None)
                dialogue=list((child or case)['learning']['exampleDialogue'])
            if 'other-restaurant' in path['code']:
                dialogue=[dict(speaker='ai',text='今のご希望には対応できません。別の方法をご希望ですか。',translation='Hiện quán không đáp ứng được yêu cầu này. Quý khách muốn chọn phương án khác không?')]
                dialogue += [turn for r in rows(OTHER_RESTAURANT) for turn in sample(r)]
                dialogue += [dict(speaker='ai',text='こちらではうどんをご注文いただけます。',translation='Ở quán này, quý khách có thể gọi udon.'),
                    dict(speaker='learner',text='うどんを一つお願いします。',translation='Cho tôi một phần udon.'),
                    dict(speaker='ai',text='お待たせしました。うどんです。',translation='Xin lỗi đã để quý khách chờ. Udon đây ạ.'),
                    dict(speaker='learner',text='注文したものです。ありがとうございます。',translation='Đúng món tôi gọi rồi. Cảm ơn ạ.')]
            opening=list(dialogue)
            if restaurant:
                dialogue += [dict(speaker='ai',text='ご注文の品をお持ちしました。内容をご確認ください。',translation='Tôi đã mang món quý khách gọi. Xin kiểm tra giúp.'),
                    dict(speaker='learner',text='はい、注文したものです。ありがとうございます。',translation='Vâng, đúng món tôi gọi. Cảm ơn.')]
                dialogue += sample(endings[0])
                dialogue += [dict(speaker='ai',text='こちらがお会計です。お支払いはどうなさいますか。',translation='Đây là hóa đơn. Quý khách thanh toán bằng cách nào?'),
                    dict(speaker='learner',text='内容を確認しました。現金で払います。',translation='Tôi đã kiểm tra hóa đơn. Tôi trả bằng tiền mặt.')]
                dialogue += [turn for r in endings[1:] for turn in sample(r)]
            else:
                dialogue += [turn for r in endings for turn in sample(r)]
            upsert(case['learning'].setdefault('branchDialogues',[]),dict(code=path['code'],title=path['title'],
                description='Tình huống được phát triển đến bước thực hiện, kiểm tra kết quả và kết thúc; các câu mẫu là diễn đạt tham khảo.',
                openingDialogue=opening,storyComplete=True,exampleDialogue=dialogue))
            total+=1
        flow['storyReview']=dict(version=1,closureStateCodes=closure,completePaths=True)
        terminal=next(s for s in flow['states'] if s['code']=='END_SUCCESS')
        terminal['summary']=endings[-1][2];terminal['name']=FINISH_TITLES[slug]
        flow['endings']['success']['message']='ご確認ありがとうございました。お気をつけて。' if restaurant else 'ご確認ありがとうございました。また必要なときはご相談ください。'
    # Stage definitions (including prerequisites) are refreshed as a separate pass.
    from author_cast import refresh_existing_cast
    refresh_existing_cast(catalog)
    from author_scene_images import refresh_scene_images
    refresh_scene_images(catalog)
    file.write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Completed {total} authored story routes across all 14 roots; {previously_short} old previews stopped early.')

if __name__=='__main__': main()
