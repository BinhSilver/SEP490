import { useEffect, useRef, useState } from "react";
import { errorMessage, getArena } from "./game/arenaClient";
import type { ShopItemView, ShopView } from "./game/arenaTypes";
import { setPlaneSkin } from "./game/useAirDefenseStore";
import { animatePlanePreview } from "./game/planePreview";
import { registerSpaceshipPack, spaceshipSource } from "./game/spaceshipSprites";
import "./air-hangar.css";
import { CpText } from "./CpText";
import { containDialogTab } from "../../shared/dialogKeyboard";

const money = (value: number) => value.toLocaleString("vi-VN");
const shopItems = (view: ShopView) => view.sections.find((s) => s.gameType === "AIR_DEFENSE")?.items ?? [];

function PlanePreview({ item }: { item: ShopItemView }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const code = item.renderCode === "PACK" ? item.code : (item.renderCode || item.code);
  useEffect(() => {
    setLoaded(false);
    setFailed(false);
    if (!canvas.current) return;
    if (item.renderCode === "PACK" && item.packPath) registerSpaceshipPack(item.code, item.packPath);
    let stop: (() => void) | null = null;
    let cancelled = false;
    void spaceshipSource(code).then((source) => {
      if (cancelled || !canvas.current) return;
      stop = animatePlanePreview(canvas.current, source?.bundle ?? null, item.image,
        source?.base ?? import.meta.env.BASE_URL, () => setLoaded(true), () => setFailed(true));
    });
    return () => { cancelled = true; stop?.(); };
  }, [code, item.code, item.image, item.packPath, item.renderCode]);
  return <span className="ad-hangar__preview" aria-hidden="true">
    <canvas ref={canvas} width={200} height={160} hidden={!loaded} />
    {!loaded && (failed ? <i className="fa-solid fa-jet-fighter" /> : <span className="ad-hangar__preview-loading">Đang tải ảnh…</span>)}
  </span>;
}

export function AirHangar({ starting, onBusyChange }: {
  starting: boolean;
  onBusyChange: (busy: boolean) => void;
}) {
  const [shop, setShop] = useState<ShopView | null>(null);
  const [balance, setBalance] = useState<number | null>(null);
  const [previewCode, setPreviewCode] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [shopError, setShopError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [purchase, setPurchase] = useState<ShopItemView | null>(null);
  const purchaseDialog = useRef<HTMLDialogElement>(null);
  const purchaseDecision = useRef<((confirmed: boolean) => void) | null>(null);
  const decidePurchase = (confirmed: boolean) => {
    const resolve = purchaseDecision.current;
    purchaseDecision.current = null;
    setPurchase(null);
    resolve?.(confirmed);
  };
  const confirmPurchase = (item: ShopItemView) => new Promise<boolean>((resolve) => {
    purchaseDecision.current = resolve;
    setPurchase(item);
  });
  useEffect(() => {
    const dialog = purchaseDialog.current;
    if (purchase && dialog && !dialog.open) dialog.showModal();
    else if (!purchase && dialog?.open) dialog.close();
  }, [purchase]);
  useEffect(() => () => { purchaseDecision.current?.(false); purchaseDecision.current = null; }, []);
  const inFlight = useRef(false);
  const alive = useRef(true);
  const initialLoad = useRef(true);

  const applyShop = (view: ShopView) => {
    setShop(view);
    setBalance(view.balance);
    const equipped = shopItems(view).find((item) => item.equipped);
    if (equipped) setPlaneSkin(equipped.code, equipped.renderCode, equipped.image, equipped.packPath, equipped.weaponTheme, equipped.name);
    setPreviewCode((code) => code ?? equipped?.code ?? shopItems(view)[0]?.code ?? null);
  };

  const loadShop = async () => {
    setShopError(null);
    try {
      const view = await getArena().api.get<ShopView>("/api/games/shop");
      if (alive.current) applyShop(view);
    } catch (e) {
      if (alive.current) setShopError(errorMessage(e, "Không tải được skin. Hãy thử lại."));
    }
  };

  useEffect(() => {
    alive.current = true;
    onBusyChange(true);
    void loadShop().finally(() => {
      initialLoad.current = false;
      if (alive.current) { setLoading(false); onBusyChange(false); }
    });
    return () => { alive.current = false; };
    // This component lives for one setup screen. Requests must not restart on parent renders.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const items = shop ? shopItems(shop) : [];
  const selected = items.find((item) => item.code === previewCode) ?? items.find((item) => item.equipped) ?? items[0];
  const disabled = starting || busy !== null || loading;

  const runAction = async (key: string, action: () => Promise<void>) => {
    if (starting || inFlight.current || initialLoad.current) return;
    inFlight.current = true;
    setBusy(key);
    setNotice("");
    onBusyChange(true);
    try { await action(); }
    finally {
      inFlight.current = false;
      if (alive.current) { setBusy(null); onBusyChange(false); }
    }
  };

  const useSkin = async (item: ShopItemView) => {
    if (starting || inFlight.current || initialLoad.current) return;
    if (item.equipped || (!item.owned && (balance === null || balance < item.price))) return;
    if (!item.owned && !await confirmPurchase(item)) return;
    await runAction(item.code, async () => {
      setShopError(null);
      try {
        const view = await getArena().api.post<ShopView>(`/api/games/shop/items/${encodeURIComponent(item.code)}/${item.owned ? "equip" : "buy"}`);
        if (alive.current) { applyShop(view); setNotice(`Đã ${item.owned ? "chọn" : "mua và chọn"} ${item.name}.`); }
      } catch (e) {
        if (alive.current) setShopError(errorMessage(e, "Chưa đổi được skin. Hãy thử lại."));
        // Recover the authoritative balance/equipment after a lost mutation response.
        try {
          const view = await getArena().api.get<ShopView>("/api/games/shop");
          if (alive.current) applyShop(view);
        } catch { /* Preserve the last known state and retry control. */ }
      }
    });
  };

  const retry = () => runAction("retry-shop", loadShop);
  return <section className="ad-hangar" aria-labelledby="ad-hangar-title" aria-busy={busy !== null || loading}>
    <div className="ad-hangar__head">
      <h2 id="ad-hangar-title">Máy bay của bạn</h2>
      <span className="ad-hangar__balance" aria-label={balance === null ? "Đang tải số dư CP" : `Số dư ${money(balance)} CP`}>
        <CpText text={`${balance === null ? "…" : money(balance)} CP`} />
      </span>
    </div>

    {!shop && !shopError && <p className="ad-hangar__hint" role="status">Đang tải skin máy bay…</p>}
    {shopError && <div className="ad-hangar__error" role="alert"><p>{shopError}</p><button type="button" disabled={disabled} onClick={() => void retry()}>Thử lại skin</button></div>}
    {shop && items.length === 0 && <p className="ad-hangar__hint">Chưa có skin máy bay trong cửa hàng.</p>}
    {items.length > 0 && <>
      <div className="ad-hangar__skins" role="group" aria-label="Xem trước skin máy bay">
        {items.map((item) => <button key={item.code} type="button"
          className={`ad-hangar__skin ${selected?.code === item.code ? "is-selected" : ""}`}
          aria-pressed={selected?.code === item.code} disabled={disabled}
          aria-label={`${item.name}, ${item.equipped ? "đang dùng" : item.owned ? "đã sở hữu" : `${money(item.price)} CP`}, xem trước`}
          onClick={() => setPreviewCode(item.code)}>
          <PlanePreview item={item} />
          <strong>{item.name}</strong>
          <small><CpText text={item.equipped ? "Đang dùng" : item.owned ? "Đã sở hữu" : `${money(item.price)} CP`} /></small>
        </button>)}
      </div>
      {selected && <div className="ad-hangar__selection">
        <button type="button" className="ad-hangar__action" disabled={disabled || selected.equipped || (!selected.owned && (balance === null || balance < selected.price))}
          onClick={() => void useSkin(selected)}>
          <CpText text={busy === selected.code ? "Đang xử lý…" : selected.equipped ? "Đang dùng" : selected.owned ? "Dùng skin này" : `Mua & dùng · ${money(selected.price)} CP`} />
        </button>
        {!selected.owned && balance !== null && balance < selected.price && <p className="ad-hangar__hint">Thiếu <CpText text={`${money(selected.price - balance)} CP`} /> để mua skin này.</p>}
      </div>}
    </>}

    <p className="ad-hangar__notice" role="status" aria-live="polite">{notice}</p>
    <dialog ref={purchaseDialog} className="ad-skin-purchase" data-game-ui-panel
      aria-labelledby="ad-skin-purchase-title"
      onKeyDown={containDialogTab} onCancel={() => decidePurchase(false)} onClose={() => decidePurchase(false)}>
      {purchase && <>
        <h3 id="ad-skin-purchase-title">Xác nhận mua máy bay</h3>
        <p>Mua <strong>{purchase.name}</strong> với <CpText text={`${money(purchase.price)} CP`} /> và dùng ngay?</p>
        <p>Số dư sau khi mua: <CpText text={`${money(Math.max(0, (balance ?? 0) - purchase.price))} CP`} /></p>
        <div className="ad-skin-purchase__actions">
          <button type="button" autoFocus onClick={() => decidePurchase(false)}>Hủy</button>
          <button type="button" data-game-ui-primary onClick={() => decidePurchase(true)}><CpText text={`Mua · ${money(purchase.price)} CP`} /></button>
        </div>
      </>}
    </dialog>
  </section>;
}
