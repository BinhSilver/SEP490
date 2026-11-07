import { AUGMENTS_POOL } from './game/useAirDefenseStore';

export type PreviewGame = 'air' | 'cat' | 'memory';
export interface PreviewEffect { id:string; label:string; group:string; }
const group = (name:string, entries:[string,string][]):PreviewEffect[] => entries.map(([id,label])=>({id,label,group:name}));

export const GAME_EFFECTS:Record<PreviewGame,PreviewEffect[]> = {
  air: [
    ...group('Máy bay và vũ khí', [['idle','Bay và động cơ'],['fire','Gõ thì bắn'],['impact','Bắn trúng và khóa mục tiêu'],['miss','Gõ sai'],['hit','Máy bay trúng đạn'],['shield','Khiên bảo vệ'],['shield_block','Khiên chặn đòn'],['danger','Cảnh báo vùng nguy hiểm'],['defeat','Máy bay bị hạ'],['charge','Nạp siêu pháo'],['beam_ready','Siêu pháo sẵn sàng'],['beam','Bắn siêu pháo'],['beam_cooldown','Siêu pháo hồi chiêu']]),
    ...group('Địch và boss', [['enemy_normal','Địch thường xuất hiện'],['enemy_fast','Địch tốc độ cao'],['enemy_mine','Mìn không gian'],['explode','Bắn hạ địch thường'],['boss','Boss xuất hiện'],['attack_boss','Mini-boss từ đối thủ'],['boss_hit','Boss nhận sát thương'],['boss_explode','Bắn hạ boss'],['boss_alert','Cảnh báo boss']]),
    ...group('Combo', [['combo_gain','Tăng combo'],['combo5','Combo 5'],['combo10','Combo 10'],['combo20','Combo 20'],['combo50','Combo 50'],['combo_break','Đứt combo']]),
    ...group('Xuất kích và chuyển đợt', [['intro_boot','Đếm ngược xuất kích'],['intro_warp','Cổng xuất kích'],['intro_ready','Xuất trận'],['wave_clear','Hoàn thành đợt'],['warp','Bay sang đợt mới'],['wave_incoming','Đợt địch mới'],['boss_wave','Đợt boss']]),
    ...group('Vật phẩm', [['loot_credit','Tinh thể xu rơi'],['loot_repair','Gói hồi máu rơi'],['loot_energy','Năng lượng siêu pháo rơi'],['loot_magnet','Hút vật phẩm về máy bay'],['pickup_credit','Nhặt xu'],['pickup_repair','Hồi máu'],['pickup_energy','Nhặt năng lượng']]),
    ...group('Lõi bổ trợ', [['augment_draft','Màn chọn lõi và lướt thẻ'],...AUGMENTS_POOL.map(card=>[`augment:${card.id}`,`Kích hoạt ${card.title.toLocaleLowerCase('vi')}`] as [string,string])]),
    ...group('Nhạc nền', [['bgm_lobby','Nhạc màn chờ'],['bgm_battle','Nhạc trong trận'],['bgm_boss','Nhạc trận boss']]),
  ],
  cat: [
    ...group('Nhân vật', [['idle','Đứng'],['run_right','Chạy phải'],['run_left','Chạy trái'],['lobby','Màn chờ'],['win','Ăn mừng chiến thắng'],['lose','Thua cuộc']]),
    ...group('Hứng cá và combo', [['catch_success','Hứng đúng'],['catch_wrong','Hứng sai'],['missed','Bỏ lỡ cá'],['reveal','Hiện đáp án đúng'],['combo3','Combo 3'],['combo6','Combo 6'],['combo9','Combo 9'],['shield_block','Khiên chặn lỗi']]),
    ...group('Thẻ đáp án', [['fish_blue','Cá xanh dương'],['fish_pink','Cá hồng'],['fish_green','Cá xanh lá'],['paw_cookie','Bánh hình chân mèo']]),
    ...group('Vật phẩm rơi', [['drop_golden_fish','Cá vàng rơi'],['drop_clock','Đồng hồ rơi'],['drop_shield','Khiên rơi'],['drop_freeze','Đóng băng rơi']]),
    ...group('Nhặt vật phẩm', [['pickup_golden_fish','Nhặt cá vàng · +50 điểm'],['pickup_clock','Nhặt đồng hồ · +5 giây'],['pickup_shield','Nhặt khiên'],['pickup_freeze','Nhặt đóng băng'],['shield','Khiên đang hoạt động'],['freeze','Đóng băng cá']]),
    ...group('Trạng thái trận', [['countdown','Đếm ngược'],['go','Bắt đầu'],['time_low','Sắp hết giờ'],['paused','Tạm dừng và tiếp tục'],['bgm','Nhạc trong trận']]),
  ],
  memory: [
    ...group('Thẻ và ghép cặp', [['hidden','Thẻ úp'],['flip','Lật thẻ'],['match','Ghép đúng'],['mismatch','Ghép sai rồi úp lại'],['combo2','Ghép liên tiếp · combo 2'],['combo5','Ghép liên tiếp · combo 5'],['complete_board','Ghép cặp cuối cùng']]),
    ...group('Bắt đầu và lượt chơi', [['dice','Tung đồng xu chọn người đi trước'],['countdown','Đếm ngược bắt đầu'],['turn_my','Đến lượt của bạn'],['turn_other','Đến lượt đối thủ'],['turn_timeout','Hết thời gian lượt'],['time_low','Sắp hết thời gian'],['paused','Tạm dừng']]),
    ...group('Kết quả', [['complete','Hoàn thành ván đơn'],['win','Thắng đối kháng'],['lose','Thua đối kháng'],['draw','Hòa'],['aborted','Ván bị hủy']]),
    ...group('Tương tác và nhạc', [['click','Bấm nút'],['bgm','Nhạc nền']]),
  ],
};

/**
 * Xem theo một skin (popup ở trang admin "Trang phục game"): chỉ những hiệu ứng thật sự đổi theo skin.
 * Máy bay: thân máy bay (bay, bắn, trúng đạn), đạn và vũ khí, siêu pháo, quầng năng lượng khi có khiên, khung nổ của skin.
 * Mèo: các hành động của nhân vật. Địch, boss, combo, chuyển đợt, vật phẩm, lõi bổ trợ, chữ trên HUD và nhạc
 * giống nhau với mọi skin nên không lặp lại ở đây.
 */
const SKIN_EFFECTS:Record<'air'|'cat',string[]> = {
  air: ['idle','fire','impact','explode','hit','shield','shield_block','defeat','charge','beam','beam_cooldown'],
  cat: ['idle','run_right','run_left','lobby','catch_success','catch_wrong','win','lose'],
};
export function previewEffects(game:PreviewGame, skinOnly:boolean):PreviewEffect[] {
  return skinOnly && game!=='memory' ? GAME_EFFECTS[game].filter(effect=>SKIN_EFFECTS[game].includes(effect.id)) : GAME_EFFECTS[game];
}

export function effectGroups(effects:PreviewEffect[]) {
  return [...new Set(effects.map(effect=>effect.group))];
}
