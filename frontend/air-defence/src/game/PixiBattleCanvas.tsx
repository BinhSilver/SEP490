import React, { useEffect, useRef } from "react";
import { Application, Assets, Container, Graphics, Sprite, Text, TextStyle, Texture } from "pixi.js";
import { useAirDefenseStore, bossHpForWave, isJapaneseField, playerPlaneSprite, playerSpaceshipCode, playerWeaponCode, isCustomPlaneSkin } from "./useAirDefenseStore";
import {loadSpaceship, setSpaceshipFrame, type SpaceshipTextures} from './spaceshipSprites';
import { GAME_CONFIG } from "./gameConfig";
import { ComboSplashOverlay } from "./ComboSplashOverlay";
import { serverNow } from "./arenaClient";
import { useVisualSettings } from "./visualSettings";
import { beamColors, drawHyperBeam } from "./HyperBeamEffect";
import { ChargeFx } from "./ChargeFx";
import { loadOrnateWeaponSprites, ornateWeaponSkin, ORNATE_WEAPON_THEMES } from './OrnateWeaponSprites';
import { WeaponVfx } from './WeaponVfx';

/** Ảnh trong public/ được Vite chép ra thư mục build: luôn đi theo base URL của bundle. */
const SPACE_ASSETS = `${import.meta.env.BASE_URL}assets/space/`;

const JP_FONT = ["Noto Sans JP", "M PLUS Rounded 1c", "sans-serif"];
const LATIN_FONT = ["Inter", "Segoe UI", "sans-serif"];

/**
 * Nhãn trên thân địch: chữ trường câu hỏi, tự xuống tối đa 2 dòng và thu nhỏ để không tràn khung
 * (nghĩa tiếng Việt có thể dài: "trường học, học viện").
 */
function createPromptLabel(
  content: string,
  opts: { japanese: boolean; boss: boolean; maxWidth: number; maxHeight: number; fill: string }
): Text {
  let fontSize = opts.boss ? (opts.japanese ? 28 : 19) : opts.japanese ? 22 : 14;
  const minSize = opts.boss ? 11 : 9;
  const style = new TextStyle({
    fontFamily: opts.japanese ? JP_FONT : LATIN_FONT,
    fontSize,
    fontWeight: "bold",
    fill: opts.fill,
    align: "center",
    wordWrap: true,
    wordWrapWidth: opts.maxWidth,
    breakWords: true,
    lineHeight: Math.round(fontSize * 1.15)
  });
  const label = new Text({ text: content, style });
  label.anchor.set(0.5);
  while ((label.height > opts.maxHeight || label.width > opts.maxWidth) && fontSize > minSize) {
    fontSize -= 1;
    style.fontSize = fontSize;
    style.lineHeight = Math.round(fontSize * 1.15);
  }
  if (label.height > opts.maxHeight || label.width > opts.maxWidth) {
    const k = Math.min(opts.maxHeight / Math.max(1, label.height), opts.maxWidth / Math.max(1, label.width));
    label.scale.set(Math.max(0.3, k));
  }
  return label;
}

interface ExplosionAnim {
  container: Container;
  sprites: (Sprite | Graphics)[];
  currentFrame: number;
  maxFrames: number;
  frameCounter: number;
  x: number;
  y: number;
}

/** Đếm ngược 3-2-1 giữa màn hình (giống Bắt cá, Lật thẻ); còn 1,2 giây thì hiện "XUẤT TRẬN!". */
function IntroCountdown() {
  const startAt = useAirDefenseStore((s) => s.match?.startAt ?? 0);
  const match = useAirDefenseStore((s) => s.match);
  const [now, setNow] = React.useState(() => serverNow());
  useEffect(() => {
    const id = window.setInterval(() => setNow(serverNow()), 100);
    return () => window.clearInterval(id);
  }, []);
  const step = Math.min(3, Math.max(1, Math.ceil((startAt - now - 1200) / 1000)));
  return (
    <div>
      <p className="ad-intro-deck">{match?.deckTitle}</p>
      <p className="ad-intro-fields">{match?.questionLabel} → {match?.answerLabel}</p>
      <h2 key={step} className="ad-intro-count font-display font-extrabold text-white drop-shadow-[0_0_30px_rgba(85,244,255,0.8)]">
        {step}
      </h2>
    </div>
  );
}

export const PixiBattleCanvas: React.FC = () => {
  const containerRef = useRef<HTMLDivElement>(null);
  const appRef = useRef<Application | null>(null);

  const dangerZoneActive = useAirDefenseStore((s) => s.dangerZoneActive);
  const screenShake = useAirDefenseStore((s) => s.screenShake);
  const inboundBoss = useAirDefenseStore((s) => s.inboundBoss);
  const waveTransition = useAirDefenseStore((s) => s.waveTransition);
  const introState = useAirDefenseStore((s) => s.introState);
  const floatingTexts = useAirDefenseStore((s) => s.floatingTexts);
  const tickGameLoop = useAirDefenseStore((s) => s.tickGameLoop);
  const hyperBeamActive = useAirDefenseStore((s) => s.hyperBeamActive);
  const hyperBeamPhase = useAirDefenseStore((s) => s.hyperBeamPhase);
  const visual = useVisualSettings();
  const [fps, setFps] = React.useState(0);
  useEffect(() => {
    if (!visual.showFps) return;
    const id = window.setInterval(() => setFps(Math.round(appRef.current?.ticker.FPS ?? 0)), 500);
    return () => window.clearInterval(id);
  }, [visual.showFps]);

  useEffect(() => {
    let isMounted = true;
    let removeResize: (() => void) | null = null;
    const container = containerRef.current;
    if (!container) return;

    const width = container.clientWidth || 800;
    const height = container.clientHeight || 500;

    const app = new Application();
    let appInitialized = false;
    let appDestroyed = false;
    let weaponVfx: WeaponVfx | null = null;
    const destroyApp = () => {
      if (!appInitialized || appDestroyed) return;
      appDestroyed = true;
      weaponVfx?.destroy();
      weaponVfx = null;
      if (appRef.current === app) appRef.current = null;
      app.destroy(true, { children: true });
    };

    (async () => {
      await app.init({
        width,
        height,
        backgroundColor: 0x070a12,
        resolution: Math.min(window.devicePixelRatio || 1, 2.0),
        autoDensity: true,
        antialias: true
      });
      appInitialized = true;

      if (!isMounted) {
        destroyApp();
        return;
      }

      appRef.current = app;
      container.appendChild(app.canvas);
      app.canvas.setAttribute("aria-hidden", "true");

      // Chờ font Nhật/Latin tải xong để nhãn trên địch đo kích thước đúng.
      try {
        const fonts = document.fonts;
        if (fonts) {
          await Promise.race([
            Promise.all([fonts.load("700 18px 'Noto Sans JP'"), fonts.load("700 14px Inter")]),
            new Promise((resolve) => setTimeout(resolve, 1500))
          ]);
        }
      } catch (e) {}
      if (!isMounted) return;

      // Layer Containers
      const bgLayer = new Container();
      const fxLayer = new Container();
      const lootLayer = new Container();
      const enemiesLayer = new Container();
      const playerLayer = new Container();
      const explosionLayer = new Container();
      const textLayer = new Container();

      app.stage.addChild(bgLayer);
      app.stage.addChild(fxLayer);
      app.stage.addChild(lootLayer);
      app.stage.addChild(enemiesLayer);
      app.stage.addChild(playerLayer);
      app.stage.addChild(explosionLayer);
      app.stage.addChild(textLayer);

      // 1. Background Parallax
      let bgSprite1: Sprite | null = null;
      let bgSprite2: Sprite | null = null;
      try {
        const bgTex = await Assets.load<Texture>(`${SPACE_ASSETS}BG.png`);
        if (!isMounted) return;
        if (bgTex) {
          bgSprite1 = new Sprite(bgTex);
          bgSprite2 = new Sprite(bgTex);
          bgSprite1.width = app.screen.width;
          bgSprite1.height = app.screen.height;
          bgSprite2.width = app.screen.width;
          bgSprite2.height = app.screen.height;
          bgSprite2.y = -app.screen.height;
          bgSprite1.alpha = bgSprite2.alpha = 0.55;
          bgLayer.addChild(bgSprite1);
          bgLayer.addChild(bgSprite2);
        }
      } catch (e) {
        if (isMounted) console.warn("Background texture fallback to starfield", e);
      }
      if (!isMounted) return;

      // Starfield particles with warp elongation capability
      const stars: { g: Graphics; size: number; depth: number; x: number; y: number; baseSpeed: number }[] = [];
      for (let i = 0; i < GAME_CONFIG.VISUALS.starfieldCount; i++) {
        const star = new Graphics();
        const depth = (i % 3 + 1) / 3;
        const size = 0.5 + depth * 1.2;
        star.circle(0, 0, size).fill({ color: 0xc1eeff, alpha: Math.random() * 0.7 + 0.3 });
        const x = Math.random() * app.screen.width;
        const y = Math.random() * app.screen.height;
        star.x = x;
        star.y = y;
        bgLayer.addChild(star);
        const speed = 0.2 + depth * 1.1;
        stars.push({ g: star, size, depth, baseSpeed: speed, x, y });
      }

      // 2. Player Ship & Exhaust Flame Setup
      const playerShipContainer = new Container();
      playerLayer.addChild(playerShipContainer);

      let shipSprite: Sprite | null = null;
      const exhaustSprites: (Sprite | Graphics)[] = [];
      let exhaustFrame = 0;
      let spaceship: SpaceshipTextures | null = null;
      let energySprite: Sprite | null = null;
      const equippedShipCode = playerSpaceshipCode();
      const weaponSkin = ornateWeaponSkin(playerWeaponCode());

      // Base Sci-Fi ship body
      const baseShipG = new Graphics();
      baseShipG
        .poly([-26, 24, 0, -32, 26, 24, 12, 18, 0, 24, -12, 18])
        .fill({ color: 0x0c2238 })
        .stroke({ color: 0x55f4ff, width: 2 });
      playerShipContainer.addChild(baseShipG);

      try {
        try { spaceship = await loadSpaceship(equippedShipCode); } catch { /* Keep the existing ship fallback. */ }
        if (!isMounted) return;
        const shipTex = spaceship?.body[0][0] ?? await Assets.load<Texture>(playerPlaneSprite());
        if (!isMounted) return;
        if (shipTex) {
          shipSprite = new Sprite(shipTex);
          if (spaceship) setSpaceshipFrame(shipSprite, shipTex);
          else shipSprite.anchor.set(0.5);
          shipSprite.scale.set(spaceship || isCustomPlaneSkin() ? Math.min(132, app.screen.width * 0.29) / Math.max(shipTex.width, isCustomPlaneSkin() ? shipTex.height : 0) : 1.1);
          playerShipContainer.addChild(shipSprite);
          // Hình vẽ dự phòng chỉ dùng khi không tải được ảnh; để lại sẽ lộ viền xanh sau máy bay màu khác.
          baseShipG.visible = false;
        }

        // Load animated exhaust flames
        for (let f = 1; f <= (spaceship ? 4 : 5); f++) {
          try {
            const exTex = spaceship?.effects[0][f - 1] ?? await Assets.load<Texture>(`${SPACE_ASSETS}FX/exhaust_0${f}.png`);
            if (!isMounted) return;
            if (exTex) {
              const exSpr = new Sprite(exTex);
              if (spaceship) setSpaceshipFrame(exSpr, exTex);
              else exSpr.anchor.set(0.5, 0);
              exSpr.y = spaceship ? 34 : 28;
              exSpr.scale.set(spaceship ? 58 / exTex.height : 0.85);
              exSpr.visible = f === 1;
              playerShipContainer.addChild(exSpr);
              exhaustSprites.push(exSpr);
            }
          } catch (e) {}
          if (!isMounted) return;
        }
      } catch (e) {}
      if (!isMounted) return;

      // Fallback animated exhaust
      if (exhaustSprites.length === 0) {
        const flameG = new Graphics();
        flameG.poly([-8, 20, 0, 36, 8, 20]).fill({ color: 0x55f4ff, alpha: 0.85 });
        playerShipContainer.addChild(flameG);
        exhaustSprites.push(flameG);
      }

      const comboAuraG = new Graphics();
      playerShipContainer.addChildAt(comboAuraG, 0);
      const engineGlow = new Graphics().ellipse(0, 36, 17, 10).fill({color:weaponSkin ? ORNATE_WEAPON_THEMES[weaponSkin].primary : spaceship?.color ?? 0x55f4ff, alpha:0.35});
      playerShipContainer.addChildAt(engineGlow, 0);
      const spawnG = new Graphics(); fxLayer.addChild(spawnG);
      const afterimages: Sprite[] = [];
      if (shipSprite) {
        for (let i = 2; i >= 1; i--) {
          const ghost = new Sprite(shipSprite.texture);
          setSpaceshipFrame(ghost, shipSprite.texture);
          ghost.scale.copyFrom(shipSprite.scale); ghost.y = i * 18; ghost.alpha = 0.12 / i;
          ghost.tint = spaceship?.color ?? 0x55f4ff; ghost.visible = false;
          playerShipContainer.addChildAt(ghost, 0); afterimages.push(ghost);
        }
      }
      if (spaceship) {
        energySprite = new Sprite(spaceship.effects[2][0]);
        setSpaceshipFrame(energySprite, spaceship.effects[2][0]); energySprite.width = 154; energySprite.height = 154;
        energySprite.visible = false; playerShipContainer.addChildAt(energySprite, 0);
      }

      playerShipContainer.x = app.screen.width / 2;
      playerShipContainer.y = app.screen.height * 0.85;

      // Only the equipped new hull requests its extra atlas. Missing art retains themed geometry.
      if (weaponSkin) {
        const atlas = await loadOrnateWeaponSprites(weaponSkin).catch(() => null);
        if (!isMounted) return;
        weaponVfx = new WeaponVfx(weaponSkin, playerShipContainer, fxLayer, atlas);
      }

      // 3. Preload Space Mine & Explosion textures
      const mineTextures: Texture[] = [];
      for (let m = 1; m <= 9; m++) {
        try {
          const mTex = await Assets.load<Texture>(`${SPACE_ASSETS}Enemies/mine_11_0${m}.png`);
          if (!isMounted) return;
          if (mTex) mineTextures.push(mTex);
        } catch (e) {}
        if (!isMounted) return;
      }

      const explosionTextures: Texture[] = [];
      if (spaceship) explosionTextures.push(...spaceship.effects[3]);
      for (let ex = 1; !spaceship && ex <= 11; ex++) {
        const numStr = ex < 10 ? `0${ex}` : `${ex}`;
        try {
          const expTex = await Assets.load<Texture>(`${SPACE_ASSETS}Explosions/explosion_1_${numStr}.png`);
          if (!isMounted) return;
          if (expTex) explosionTextures.push(expTex);
        } catch (e) {}
        if (!isMounted) return;
      }

      // Preload Enemy Sprites
      let enemyNormalTex: Texture | null = null;
      let enemyFastTex: Texture | null = null;
      try {
        enemyNormalTex = await Assets.load<Texture>(`${SPACE_ASSETS}Enemies/enemy_1_b_m.png`);
        if (!isMounted) return;
        enemyFastTex = await Assets.load<Texture>(`${SPACE_ASSETS}Enemies/enemy_2_r_m.png`);
        if (!isMounted) return;
      } catch (e) {}
      if (!isMounted) return;

      // Active Enemy Nodes
      const enemyNodes = new Map<
        string,
        {
          container: Container;
          sprite: Sprite | null;
          textPrompt: Text;
          badgeG: Graphics;
          bossHpBar?: Graphics;
          mineFrame: number;
          bornAt: number;
          missUntil: number;
          portal: Graphics;
          scan: Graphics;
          badgeY: number;
          badgeW: number;
          badgeH: number;
        }
      >();

      // Active Loot Nodes
      const lootNodes = new Map<string, { container: Container; g: Graphics; rotSpeed: number }>();

      const activeExplosions: ExplosionAnim[] = [];
      const laserG = new Graphics();
      const hyperBeamG = new Graphics();
      let lastBeamPhase = "idle";
      let beamPhaseStarted = performance.now();
      const cosmeticBulletsLayer = new Container();
      fxLayer.addChild(laserG);
      fxLayer.addChild(hyperBeamG);
      fxLayer.addChild(cosmeticBulletsLayer);
      const chargeFx = new ChargeFx(fxLayer, explosionLayer);

      interface CosmeticTypingBullet {
        g: Graphics | Sprite;
        x: number;
        y: number;
        vx: number;
        vy: number;
        life: number;
        maxLife: number;
      }
      const activeCosmeticBullets: CosmeticTypingBullet[] = [];
      const activeMuzzleFlashes: { g: Graphics; life: number }[] = [];
      const particles: {g:Graphics; vx:number; vy:number; life:number; max:number}[] = [];
      const emitParticle = (x:number,y:number,vx:number,vy:number,color:number,life:number,size=2) => {
        const settings = useVisualSettings.getState();
        if (settings.reducedMotion || settings.particles === 'off' || particles.length >= (settings.particles === 'high' ? 100 : 25)) return;
        const g = new Graphics().rect(-size/2,-size/2,size,size).fill({color});
        g.position.set(x,y); fxLayer.addChild(g); particles.push({g,vx,vy,life,max:life});
      };
      let lastKnownTypingBulletCount = useAirDefenseStore.getState().typingBulletCount;
      let barrelSide = -1;
      let lastLaserObject: { x: number; y: number } | null = null;

      // Helper: Trigger Explosion Animation
      const spawnExplosion = (x: number, y: number) => {
        const count = useVisualSettings.getState().particles === 'high' ? 12 : 4;
        for (let i = 0; i < count; i++) {
          const angle = i * Math.PI * 2 / count;
          emitParticle(x,y,Math.cos(angle)*3,Math.sin(angle)*3, i%3 === 0 ? 0xffc857 : spaceship?.color ?? 0x55f4ff,26);
        }
        const expCont = new Container();
        expCont.x = x;
        expCont.y = y;
        explosionLayer.addChild(expCont);

        if (explosionTextures.length > 0) {
          const sprites: Sprite[] = [];
          explosionTextures.forEach((tex, idx) => {
            const s = new Sprite(tex);
            if (spaceship) setSpaceshipFrame(s, tex);
            else s.anchor.set(0.5);
            s.scale.set(spaceship ? 110 / tex.width : 0.85);
            s.visible = idx === 0;
            expCont.addChild(s);
            sprites.push(s);
          });

          activeExplosions.push({
            container: expCont,
            sprites,
            currentFrame: 0,
            maxFrames: explosionTextures.length,
            frameCounter: 0,
            x,
            y
          });
        } else {
          const burstG = new Graphics();
          burstG.circle(0, 0, 24).fill({ color: 0xffa040, alpha: 0.9 });
          expCont.addChild(burstG);
          activeExplosions.push({
            container: expCont,
            sprites: [burstG],
            currentFrame: 0,
            maxFrames: 6,
            frameCounter: 0,
            x,
            y
          });
        }
      };

      // 4. Main 60 FPS Render Loop
      if (!isMounted) return;
      let lastTime = performance.now();
      let exhaustTick = 0;
      let prevTargetIds = new Set<string>();
      let lastShipHp = useAirDefenseStore.getState().hp;
      let lastShipTyping = useAirDefenseStore.getState().typingBulletCount;
      let shipHitUntil = 0, shipShootUntil = 0, shipAction = 0, shipActionStart = performance.now();
      let lastWrongInputs = useAirDefenseStore.getState().wrongInputs;
      let trailTick = 0;
      const entranceAt = performance.now();

      app.ticker.add(() => {
        if (!isMounted) return;
        const now = performance.now();
        // Tab bị ẩn rồi quay lại: không để địch "nhảy cóc" hàng trăm khung hình trong một lần.
        const delta = Math.min(4, Math.max(0, (now - lastTime) / 16.66));
        lastTime = now;
        const prefs = useVisualSettings.getState();
        const frameState = useAirDefenseStore.getState();
        const motion = !prefs.reducedMotion;
        const sceneColor = weaponVfx?.theme.primary ?? spaceship?.color ?? 0x55f4ff;

        const currentTransition = useAirDefenseStore.getState().waveTransition;
        const isWarping = currentTransition.active && currentTransition.phase === "warp";
        const isIntro = useAirDefenseStore.getState().introState.active;

        // Background Parallax Scroll & Warp Speed
        const bgSpeed = motion ? GAME_CONFIG.VISUALS.bgScrollSpeed * (isWarping ? 3 : 0.15) : 0;
        if (bgSprite1 && bgSprite2) {
          bgSprite1.y += bgSpeed * delta;
          bgSprite2.y += bgSpeed * delta;
          if (bgSprite1.y >= app.screen.height) bgSprite1.y = bgSprite2.y - app.screen.height;
          if (bgSprite2.y >= app.screen.height) bgSprite2.y = bgSprite1.y - app.screen.height;
        }

        // Stars warp streak animation
        stars.forEach((s, index) => {
          s.g.visible = prefs.particles !== 'off' && (prefs.particles === 'high' || index % 3 === 0);
          const mult = motion ? (isWarping ? 8 : frameState.combo >= 20 ? 1.3 : 1) : 0;
          s.y += s.baseSpeed * mult * delta;
          if (s.y > app.screen.height) {
            s.y = 0;
            s.x = Math.random() * app.screen.width;
          }
          s.g.x = s.x;
          s.g.y = s.y;

          s.g.clear();
          if (isWarping && motion) {
            s.g
              .moveTo(0, 0)
              .lineTo(0, 24 * s.baseSpeed)
              .stroke({ color: 0x55f4ff, width: 2, alpha: 0.9 });
          } else {
            s.g.circle(0, 0, s.size).fill({ color: 0xc1eeff, alpha: 0.25 + s.depth * 0.4 });
          }
        });

        // Player Ship Intro Entrance Animation
        const entrance = motion ? Math.max(0,1-(now-entranceAt)/600) : 0;
        playerShipContainer.y = app.screen.height * 0.8 + entrance * entrance * 100 + (motion ? Math.sin(now / 900)*1.5 : 0);
        spawnG.clear();
        if (entrance > 0 && prefs.particles !== 'off') {
          spawnG.moveTo(playerShipContainer.x,app.screen.height).lineTo(playerShipContainer.x,playerShipContainer.y-70).stroke({color:sceneColor,width:4,alpha:entrance*.6});
          spawnG.ellipse(playerShipContainer.x,app.screen.height*.8,70*(1-entrance),18*(1-entrance)).stroke({color:sceneColor,width:2,alpha:entrance});
        }
        engineGlow.alpha = motion ? 0.65 + Math.sin(now / 550) * 0.15 : 0.65;
        engineGlow.visible = frameState.hp > 0;
        trailTick += delta;
        if (trailTick > 5 && frameState.hp > 0) {
          trailTick = 0;
          emitParticle(playerShipContainer.x + (Math.random()-.5)*14,playerShipContainer.y+40,0,1.5,sceneColor,22,1.5);
        }
        particles.forEach((p) => { p.g.x+=p.vx*delta; p.g.y+=p.vy*delta; p.life-=delta; p.g.alpha=Math.max(0,p.life/p.max)*.7; });
        for (let i=particles.length-1;i>=0;i--) {
          if (particles[i].life <= 0 || !motion || prefs.particles === 'off') { particles[i].g.destroy(); particles.splice(i,1); }
        }
        afterimages.forEach((g) => {
          g.visible = motion && frameState.hp > 0 && (isWarping || entrance > 0);
          if (shipSprite) { setSpaceshipFrame(g,shipSprite.texture); g.scale.copyFrom(shipSprite.scale); }
        });

        // Exhaust flame cycle
        exhaustTick += delta;
        if (exhaustTick > 3) {
          exhaustTick = 0;
          if (exhaustSprites.length > 1) {
            exhaustSprites[exhaustFrame].visible = false;
            exhaustFrame = (exhaustFrame + 1) % exhaustSprites.length;
            exhaustSprites[exhaustFrame].visible = true;
          }
        }

        // Tick Game Logic Store
        tickGameLoop(delta);
        const state = useAirDefenseStore.getState();
        if (state.hp < lastShipHp) shipHitUntil = now + 600;
        if (state.hp <= 0 && lastShipHp > 0) spawnExplosion(playerShipContainer.x, playerShipContainer.y);
        lastShipHp = state.hp;
        if (shipSprite) shipSprite.visible = state.hp > 0;
        else baseShipG.visible = state.hp > 0;
        exhaustSprites.forEach((sprite, index) => { sprite.visible = state.hp > 0 && index === exhaustFrame; });
        if (spaceship && shipSprite) {
          if (state.typingBulletCount > lastShipTyping || state.lastLaserTarget) shipShootUntil = now + 350;
          lastShipTyping = state.typingBulletCount;
          const action = now < shipHitUntil ? 3 : now < shipShootUntil || state.hyperBeamPhase === 'charge' || state.hyperBeamPhase === 'firing' ? 2 : isWarping || isIntro ? 1 : 0;
          if (action !== shipAction) { shipAction = action; shipActionStart = now; }
          const step = Math.floor((now - shipActionStart) / 150);
          // Forward acceleration keeps the hull straight; bank poses require actual lateral steering.
          const row = action === 1 ? 0 : action;
          setSpaceshipFrame(shipSprite, spaceship.body[row][action >= 2 ? Math.min(3, step) : step % 4]);
          if (energySprite) {
            setSpaceshipFrame(energySprite, spaceship.effects[2][Math.floor(now / 150) % 4]);
            energySprite.visible = !weaponVfx && state.hp > 0 && (state.shield > 0 || state.combo >= 5 || state.hyperBeamCharge >= 100 || state.hyperBeamPhase === 'charge' || state.hyperBeamPhase === 'firing');
            energySprite.alpha = 0.45;
          }
        }

        // Render Enemies
        const currentTargets = useAirDefenseStore.getState().targets;
        const activeIds = new Set<string>();
        const wrongChanged = frameState.wrongInputs > lastWrongInputs;
        lastWrongInputs = frameState.wrongInputs;
        const closestTarget = wrongChanged ? currentTargets.filter(t => !t.isDead).sort((a,b) => b.posY-a.posY)[0]?.id : null;

        currentTargets.forEach((t) => {
          if (t.isDead) return;
          activeIds.add(t.id);

          let node = enemyNodes.get(t.id);
          const isBoss = t.type === "MINI_BOSS";

          if (!node) {
            const enemyCont = new Container();
            let enemySpr: Sprite | null = null;

            if (isBoss) {
              if (enemyFastTex) {
                enemySpr = new Sprite(enemyFastTex);
                enemySpr.anchor.set(0.5);
                enemySpr.scale.set(GAME_CONFIG.ENEMIES.bossScale);
                enemyCont.addChild(enemySpr);
              }
              // Double Pulsating Boss Shockwave Aura (mini-boss đối thủ gửi sang có vầng tím)
              const auraG = new Graphics();
              auraG.circle(0, 0, 68).stroke({ color: t.fromAttack ? 0xa979ff : 0xff1a4b, width: 3, alpha: 0.85 });
              auraG.circle(0, 0, 84).stroke({ color: t.fromAttack ? 0xc3a6ff : 0xff3366, width: 1.5, alpha: 0.5 });
              enemyCont.addChild(auraG);
            } else if (t.type === "SPACE_MINE" && mineTextures.length > 0) {
              enemySpr = new Sprite(mineTextures[0]);
              enemySpr.anchor.set(0.5);
              enemySpr.scale.set(0.65);
              enemyCont.addChild(enemySpr);
            } else if (t.type === "MONSTER_FAST" && enemyFastTex) {
              enemySpr = new Sprite(enemyFastTex);
              enemySpr.anchor.set(0.5);
              enemySpr.scale.set(0.65);
              enemyCont.addChild(enemySpr);
            } else if (enemyNormalTex) {
              enemySpr = new Sprite(enemyNormalTex);
              enemySpr.anchor.set(0.5);
              enemySpr.scale.set(0.65);
              enemyCont.addChild(enemySpr);
            } else {
              const g = new Graphics();
              g.poly([-16, -18, 0, 18, 16, -18, 0, -10])
                .fill({ color: isBoss ? 0xff0055 : t.type === "MONSTER_FAST" ? 0xff4d6d : 0x00d2ff })
                .stroke({ color: 0xffffff, width: 1.5 });
              enemyCont.addChild(g);
            }

            // Khung nhãn câu hỏi (prompt của thẻ) — đủ chỗ cho 2 dòng
            const badgeG = new Graphics();
            const badgeW = isBoss ? 176 : 128;
            const badgeH = isBoss ? 66 : 54;
            const badgeY = isBoss ? -110 : -78;
            const badgeStroke = isBoss ? (t.fromAttack ? 0xa979ff : 0xff1a4b) : 0x55f4ff;

            badgeG
              .roundRect(-badgeW / 2, badgeY, badgeW, badgeH, 10)
              .fill({ color: isBoss ? (t.fromAttack ? 0x1a0a33 : 0x240512) : 0x070e1c, alpha: 0.95 })
              .stroke({ color: badgeStroke, width: isBoss ? 2.5 : 1.5 });
            enemyCont.addChild(badgeG);
            const scan = new Graphics(); enemyCont.addChild(scan);
            const portal = new Graphics(); enemyCont.addChildAt(portal,0);

            // Kanji / cách đọc dùng font Nhật; nghĩa / Hán Việt dùng font Latin.
            const questionField = useAirDefenseStore.getState().match?.questionField;
            const textPrompt = createPromptLabel(t.prompt, {
              japanese: isJapaneseField(questionField),
              boss: isBoss,
              maxWidth: badgeW - 14,
              maxHeight: badgeH - 8,
              fill: isBoss ? (t.fromAttack ? "#d8c8ff" : "#ff7d95") : "#eefcff"
            });
            textPrompt.x = 0;
            textPrompt.y = badgeY + badgeH / 2;
            enemyCont.addChild(textPrompt);

            // Mini-boss của đối thủ: ghi tên người gửi dưới khung
            if (isBoss && t.fromAttack && t.fromName) {
              const fromText = new Text({
                text: t.fromName,
                style: new TextStyle({
                  fontFamily: LATIN_FONT,
                  fontSize: 11,
                  fontWeight: "bold",
                  fill: "#c3a6ff",
                  align: "center"
                })
              });
              fromText.anchor.set(0.5);
              fromText.y = badgeY + badgeH + 10;
              if (fromText.width > badgeW) fromText.scale.set(badgeW / fromText.width);
              enemyCont.addChild(fromText);
            }

            // Boss HP Segment Bar
            let bossHpBar: Graphics | undefined;
            if (isBoss) {
              bossHpBar = new Graphics();
              enemyCont.addChild(bossHpBar);
            }

            enemiesLayer.addChild(enemyCont);
            node = { container: enemyCont, sprite: enemySpr, textPrompt, badgeG, bossHpBar, mineFrame: 0, bornAt:0,missUntil:0,portal,scan,badgeY,badgeW,badgeH };
            enemyNodes.set(t.id, node);
          }

          // Update Boss HP Bar
          if (isBoss && node.bossHpBar) {
            node.bossHpBar.clear();
            const currentHp = t.currentHp || 1;
            const maxHp = t.maxHp || 5;
            const barW = 150;
            const barH = 8;
            const barY = -124;

            node.bossHpBar
              .roundRect(-barW / 2, barY, barW, barH, 4)
              .fill({ color: 0x330011, alpha: 0.85 })
              .stroke({ color: 0xff1a4b, width: 1.5 });

            const fillW = Math.max(0, (currentHp / maxHp) * (barW - 2));
            const hpColor = currentHp > 2 ? 0xff3366 : 0xffa000;
            node.bossHpBar
              .roundRect(-barW / 2 + 1, barY + 1, fillW, barH - 2, 3)
              .fill({ color: hpColor, alpha: 1 });
          }

          // Animate rotating space mine
          if (t.type === "SPACE_MINE" && mineTextures.length > 0 && node.sprite) {
            node.mineFrame = (node.mineFrame + 0.15 * delta) % mineTextures.length;
            node.sprite.texture = mineTextures[Math.floor(node.mineFrame)];
          }

          // Labels stay stable; lateral movement is reserved for a brief wrong-answer ripple.
          if (!node.bornAt && t.posY >= 10) node.bornAt = now;
          if (closestTarget === t.id) node.missUntil = now + 220;
          const ripple = motion && now < node.missUntil ? Math.sin((node.missUntil-now)*.06)*2 : 0;
          const birth = !motion || !node.bornAt ? 1 : Math.min(1,(now-node.bornAt)/320);
          node.container.alpha = .5+birth*.5;
          node.container.scale.set(.9+birth*.1);
          node.badgeG.tint = now < node.missUntil ? 0xff8aa0 : 0xffffff;
          node.portal.clear();
          if (birth < 1 && prefs.particles !== 'off') node.portal.ellipse(0,8,54+birth*15,12).stroke({color:0x55f4ff,width:2,alpha:1-birth});
          node.scan.clear();
          if (motion && prefs.particles === 'high') {
            const scanY = node.badgeY + ((now/35)%node.badgeH);
            node.scan.rect(-node.badgeW/2+4,scanY,node.badgeW-8,1).fill({color:0x55f4ff,alpha:.1});
          }

          // Giữ cả khung nhãn trong màn hình
          const edge = Math.min(isBoss ? 90 : 66, app.screen.width / 2);
          node.container.x = Math.max(edge, Math.min(app.screen.width - edge, (t.posX / 100) * app.screen.width + ripple));
          node.container.y = (t.posY / 100) * app.screen.height;
        });

        // Detect newly destroyed targets for explosion VFX
        prevTargetIds.forEach((id) => {
          if (!activeIds.has(id)) {
            const oldNode = enemyNodes.get(id);
            if (oldNode) {
              spawnExplosion(oldNode.container.x, oldNode.container.y);
            }
          }
        });
        prevTargetIds = activeIds;

        // Clean dead nodes
        enemyNodes.forEach((node, id) => {
          if (!activeIds.has(id)) {
            enemiesLayer.removeChild(node.container);
            node.container.destroy({ children: true });
            enemyNodes.delete(id);
          }
        });

        // ====================================================================
        // RENDER LOOT ITEMS (Credit Diamonds, Repair Packs, Hyper Orbs)
        // ====================================================================
        const currentLoot = useAirDefenseStore.getState().lootItems;
        const activeLootIds = new Set<string>();

        currentLoot.forEach((item) => {
          if (item.collected) return;
          activeLootIds.add(item.id);

          let node = lootNodes.get(item.id);
          if (!node) {
            const cont = new Container();
            const g = new Graphics();

            if (item.type === "CREDIT_CRYSTAL") {
              // Glowing Gold / Amber Diamond Crystal
              g.poly([0, -10, 8, 0, 0, 10, -8, 0])
                .fill({ color: 0xffd700, alpha: 0.95 })
                .stroke({ color: 0xfff0a0, width: 1.5 });
              g.circle(0, 0, 12).stroke({ color: 0xffaa00, width: 1, alpha: 0.5 });
            } else if (item.type === "REPAIR_PACK") {
              // Glowing Green Repair Nano Box
              g.roundRect(-8, -8, 16, 16, 3)
                .fill({ color: 0x00e676, alpha: 0.9 })
                .stroke({ color: 0xffffff, width: 1.5 });
              // White cross
              g.rect(-2, -5, 4, 10).fill({ color: 0xffffff });
              g.rect(-5, -2, 10, 4).fill({ color: 0xffffff });
            } else {
              // Glowing Violet Plasma Hyper Orb
              g.circle(0, 0, 9).fill({ color: 0xba68c8, alpha: 0.95 }).stroke({ color: 0xe1bee7, width: 1.5 });
              g.circle(0, 0, 14).stroke({ color: 0x9c27b0, width: 1, alpha: 0.6 });
            }

            cont.addChild(g);
            lootLayer.addChild(cont);
            node = { container: cont, g, rotSpeed: (Math.random() - 0.5) * 0.1 };
            lootNodes.set(item.id, node);
          }

          node.container.x = (item.x / 100) * app.screen.width;
          node.container.y = (item.y / 100) * app.screen.height;
          node.g.rotation += node.rotSpeed * delta;
        });

        // Clean collected/expired loot
        lootNodes.forEach((node, id) => {
          if (!activeLootIds.has(id)) {
            lootLayer.removeChild(node.container);
            node.container.destroy({ children: true });
            lootNodes.delete(id);
          }
        });

        // Update Active Explosions
        for (let i = activeExplosions.length - 1; i >= 0; i--) {
          const exp = activeExplosions[i];
          exp.frameCounter += delta;
          if (exp.frameCounter >= 2) {
            exp.frameCounter = 0;
            if (exp.sprites[exp.currentFrame]) {
              exp.sprites[exp.currentFrame].visible = false;
            }
            exp.currentFrame++;
            if (exp.currentFrame < exp.maxFrames && exp.sprites[exp.currentFrame]) {
              exp.sprites[exp.currentFrame].visible = true;
            } else {
              explosionLayer.removeChild(exp.container);
              exp.container.destroy({ children: true });
              activeExplosions.splice(i, 1);
            }
          }
        }

        // Laser Lock-on Beam Render
        const laser = useAirDefenseStore.getState().lastLaserTarget;
        laserG.clear();
        if (laser) {
          const startX = playerShipContainer.x;
          const startY = playerShipContainer.y - (spaceship ? 46 : 32);
          const endX = (laser.x / 100) * app.screen.width;
          const endY = (laser.y / 100) * app.screen.height;
          if (weaponVfx) {
            weaponVfx.drawLockOn(laserG, { x: startX, y: startY }, { x: endX, y: endY }, now, prefs);
            if (laser !== lastLaserObject) {
              weaponVfx.impact(endX, endY, prefs);
              weaponVfx.impact(startX, startY, prefs, 0.35);
            }
          } else {
            laserG
              .moveTo(startX - 8, startY)
              .lineTo(endX, endY)
              .stroke({ color: 0x55f4ff, width: GAME_CONFIG.VISUALS.laserBeamWidth, alpha: 0.95 });
            laserG
              .moveTo(startX + 8, startY)
              .lineTo(endX, endY)
              .stroke({ color: 0x55f4ff, width: GAME_CONFIG.VISUALS.laserBeamWidth, alpha: 0.95 });

            laserG
              .moveTo(startX, startY)
              .lineTo(endX, endY)
              .stroke({ color: 0xa979ff, width: GAME_CONFIG.VISUALS.laserGlowWidth, alpha: 0.35 });
          }
        }
        lastLaserObject = laser;

        // Each typing event emits cosmetic projectiles; scoring stays in the store.
        const currentTypingCount = useAirDefenseStore.getState().typingBulletCount;
        if (currentTypingCount > lastKnownTypingBulletCount) {
          const newBulletsCount = Math.min(6, currentTypingCount - lastKnownTypingBulletCount);
          lastKnownTypingBulletCount = currentTypingCount;

          const currentTargets = useAirDefenseStore.getState().targets;

          for (let k = 0; k < newBulletsCount; k++) {
            barrelSide = -barrelSide;
            const startX = playerShipContainer.x + barrelSide * 14;
            const startY = playerShipContainer.y - (spaceship ? 46 : 32);

            const targetedPos = useAirDefenseStore.getState().typingBulletTarget;
            let targetX = startX;
            let targetY = -100;
            if (targetedPos) {
              targetX = (targetedPos.x / 100) * app.screen.width;
              targetY = (targetedPos.y / 100) * app.screen.height;
            } else if (currentTargets.length > 0) {
              let lowestTarget = currentTargets[0];
              for (const t of currentTargets) {
                if (t.posY > lowestTarget.posY) lowestTarget = t;
              }
              targetX = (lowestTarget.posX / 100) * app.screen.width;
              targetY = (lowestTarget.posY / 100) * app.screen.height;
            }

            if (weaponVfx) {
              weaponVfx.fire({ x: startX, y: startY }, { x: targetX, y: targetY },
                Boolean(targetedPos || currentTargets.length > 0), currentTypingCount + k, prefs);
              continue;
            }

            const dx = targetX - startX;
            const dy = targetY - startY;
            const dist = Math.hypot(dx, dy) || 1;
            const speed = 22 + Math.random() * 4;

            const vx = (dx / dist) * speed + (Math.random() - 0.5) * 1.5;
            const vy = (dy / dist) * speed + (Math.random() - 0.5) * 1.5;

            const bulletG: Sprite | Graphics = spaceship ? new Sprite(spaceship.effects[1][currentTypingCount % 4]) : new Graphics();
            if (bulletG instanceof Sprite) {
              setSpaceshipFrame(bulletG, bulletG.texture); bulletG.width = 12; bulletG.height = 32;
            } else { bulletG
              .rect(-1.5, -7, 3, 14)
              .fill({ color: 0x55f4ff, alpha: 0.95 });
            bulletG
              .rect(-0.5, -5, 1, 10)
              .fill({ color: 0xffffff, alpha: 1.0 });
            }

            bulletG.x = startX;
            bulletG.y = startY;
            bulletG.rotation = Math.atan2(vy, vx) + Math.PI / 2;

            cosmeticBulletsLayer.addChild(bulletG);

            activeCosmeticBullets.push({
              g: bulletG,
              x: startX,
              y: startY,
              vx,
              vy,
              life: 0,
              maxLife: 40
            });

            const muzzleG = new Graphics();
            muzzleG.circle(0, 0, 4.5).fill({ color: 0x55f4ff, alpha: 0.9 });
            muzzleG.circle(0, 0, 2).fill({ color: 0xffffff, alpha: 1.0 });
            muzzleG.x = startX;
            muzzleG.y = startY;
            cosmeticBulletsLayer.addChild(muzzleG);

            activeMuzzleFlashes.push({ g: muzzleG, life: 0 });
          }
        }

        // Ticker-owned flashes cannot outlive a destroyed/remounted canvas.
        for (let i = activeMuzzleFlashes.length - 1; i >= 0; i--) {
          const flash = activeMuzzleFlashes[i];
          flash.life += delta;
          if (flash.life >= 3) { flash.g.destroy(); activeMuzzleFlashes.splice(i, 1); }
        }

        // Update Active Cosmetic Bullets
        for (let i = activeCosmeticBullets.length - 1; i >= 0; i--) {
          const b = activeCosmeticBullets[i];
          b.x += b.vx * delta;
          b.y += b.vy * delta;
          b.life += delta;
          b.g.x = b.x;
          b.g.y = b.y;

          if (b.y < 40) {
            b.g.alpha = Math.max(0, b.y / 40);
          }

          if (b.y < -30 || b.g.alpha <= 0 || b.life >= b.maxLife) {
            cosmeticBulletsLayer.removeChild(b.g);
            b.g.destroy();
            activeCosmeticBullets.splice(i, 1);
          }
        }

        // Dynamic Combo Hull & Engine Aura Render
        const currentCombo = useAirDefenseStore.getState().combo;
        comboAuraG.clear();
        if (currentCombo >= 5 || frameState.hyperBeamCharge >= 100) {
          comboAuraG.visible = true;
          const auraTime = motion ? performance.now() * 0.001 : 0;
          const radius = 50 + Math.sin(auraTime * 2) * 2;

          if (currentCombo >= 30) {
            // Tier 5: Supernova Cosmic Aurora
            comboAuraG.circle(0, 0, radius + 10).stroke({ color: sceneColor, width: 3, alpha: 0.8 });
            comboAuraG.circle(0, 0, radius + 4).stroke({ color: 0xffd700, width: 2, alpha: 0.6 });
            comboAuraG.circle(0, 0, radius + 18).stroke({ color: sceneColor, width: 1, alpha: 0.4 });
            for (let i = 0; i < 4; i++) {
              const angle = auraTime * 2 + (i * Math.PI) / 2;
              const ox = Math.cos(angle) * (radius + 12);
              const oy = Math.sin(angle) * (radius + 12);
              comboAuraG.circle(ox, oy, 3.5).fill({ color: sceneColor, alpha: 0.9 });
            }
          } else if (currentCombo >= 20) {
            // Tier 4: Godlike Cyber Flame & Lightning
            comboAuraG.circle(0, 0, radius + 8).stroke({ color: sceneColor, width: 2.5, alpha: 0.75 });
            comboAuraG.circle(0, 0, radius + 2).stroke({ color: sceneColor, width: 1.5, alpha: 0.5 });
            for (let i = 0; i < 3; i++) {
              const angle = auraTime * 2.5 + (i * Math.PI * 2) / 3;
              const ox = Math.cos(angle) * (radius + 8);
              const oy = Math.sin(angle) * (radius + 8);
              comboAuraG.circle(ox, oy, 3).fill({ color: sceneColor, alpha: 0.85 });
            }
          } else if (currentCombo >= 10) {
            // Tier 3: Hyper Plasma Violet
            comboAuraG.circle(0, 0, radius + 6).stroke({ color: sceneColor, width: 2, alpha: 0.7 });
            comboAuraG.circle(0, 0, radius).stroke({ color: sceneColor, width: 1.5, alpha: 0.45 });
            for (let i = 0; i < 2; i++) {
              const angle = auraTime * 1.8 + i * Math.PI;
              const ox = Math.cos(angle) * (radius + 6);
              const oy = Math.sin(angle) * (radius + 6);
              comboAuraG.circle(ox, oy, 2.5).fill({ color: sceneColor, alpha: 0.8 });
            }
          } else {
            // Tier 2: Heat Streak Gold
            comboAuraG.circle(0, 0, radius + 4).stroke({ color: 0xffc857, width: 1.8, alpha: 0.65 });
            comboAuraG.circle(0, 0, radius - 2).stroke({ color: 0xffaa00, width: 1, alpha: 0.35 });
          }
        } else {
          comboAuraG.visible = false;
        }

        const beamPhase = useAirDefenseStore.getState().hyperBeamPhase;
        if (beamPhase !== lastBeamPhase) {
          lastBeamPhase = beamPhase;
          beamPhaseStarted = performance.now();
        }
        weaponVfx?.update(now, delta, {
          alive: state.hp > 0, charge: state.hyperBeamCharge, combo: state.combo,
          phase: beamPhase, shield: state.shield
        }, prefs, spaceship ? -46 : -32);
        const beamVisual = {
          phase: beamPhase, skin: playerWeaponCode(),
          x: playerShipContainer.x, y: playerShipContainer.y - (spaceship ? 46 : 32),
          now: performance.now(), elapsed: performance.now() - beamPhaseStarted,
          motion, particles: prefs.particles, color: sceneColor
        };
        drawHyperBeam(hyperBeamG, beamVisual);
        chargeFx.update({ ...beamVisual, colors: beamColors(beamVisual.skin, sceneColor) });
      });

      // Handle Resize smoothly
      const onResize = () => {
        if (!isMounted || !containerRef.current || appRef.current !== app) return;
        const newW = containerRef.current.clientWidth;
        const newH = containerRef.current.clientHeight;
        appRef.current.renderer.resize(newW, newH);
        playerShipContainer.x = newW / 2;
        playerShipContainer.y = newH * 0.8;
        if (shipSprite && spaceship) shipSprite.scale.set(Math.min(132,newW*.29)/spaceship.body[0][0].width);
        if (bgSprite1 && bgSprite2) {
          bgSprite1.width = newW;
          bgSprite1.height = newH;
          bgSprite2.width = newW;
          bgSprite2.height = newH;
          // Keep the two background tiles adjacent after a viewport height change.
          bgSprite1.y = 0;
          bgSprite2.y = -newH;
        }
      };

      window.addEventListener("resize", onResize);
      // Khung canvas còn đổi cỡ theo bố cục (thanh đối thủ, bàn phím ảo...), không chỉ theo cửa sổ.
      const observer = typeof ResizeObserver !== "undefined" ? new ResizeObserver(() => onResize()) : null;
      observer?.observe(container);
      removeResize = () => {
        window.removeEventListener("resize", onResize);
        observer?.disconnect();
      };
    })().catch((error) => {
      destroyApp();
      if (isMounted) console.error("Could not initialize the battle canvas", error);
    });

    return () => {
      isMounted = false;
      removeResize?.();
      destroyApp();
    };
  }, []);

  return (
    <div
      ref={containerRef}
      className={`relative w-full h-full rounded-2xl overflow-hidden border border-cyan-300/30 bg-[#070a12] transition-transform duration-75 ${
        screenShake && visual.screenShake && !visual.reducedMotion ? "ad-event-shake" : ""
      }`}
      style={{"--shake-size": hyperBeamActive ? "4px" : inboundBoss ? "6px" : "2px"} as React.CSSProperties}
    >
      {hyperBeamPhase === "charge" && <div className="ad-beam-caption"><span>Siêu pháo · Nạp năng lượng</span><strong>CHUẨN BỊ KHAI HỎA</strong></div>}
      {visual.showFps && <span className="ad-fps">{fps} FPS</span>}

      {/* Danger Zone Flashing Vignette */}
      {dangerZoneActive && (
        <div className="pointer-events-none absolute inset-0 border-4 border-rose-500/80 shadow-[inset_0_0_60px_rgba(255,77,109,.6)] animate-pulse z-10" />
      )}

      {/* Floating Pickup Text Popups */}
      {floatingTexts.map((ft) => (
        <div
          key={ft.id}
          className="pointer-events-none absolute -translate-x-1/2 font-mono text-xs font-extrabold tracking-wider z-25 transition-all duration-75"
          style={{
            left: `${ft.x}%`,
            top: `${ft.y}%`,
            color: ft.color,
            opacity: ft.opacity,
            textShadow: `0 0 10px ${ft.color}`
          }}
        >
          {ft.id.startsWith("hit-") ? `正解 ${ft.text}` : ft.text}
        </div>
      ))}

      {/* ⚡ COMBO MILESTONE & REACTION SPLASH BADGE OVERLAY */}
      <ComboSplashOverlay />

      {/* 🚀 MATCH LAUNCH INTRO OVERLAY (Cinematic 3-Stage Bootup) */}
      {introState.active && (
        <div className="absolute inset-0 z-40 flex flex-col items-center justify-center bg-black/65 backdrop-blur-sm transition-all duration-300">
          <div className="text-center px-4 max-w-lg">
            {(introState.phase === "boot" || introState.phase === "warpin") && <IntroCountdown />}

            {introState.phase === "ready" && (
              <div className="animate-pulse">
                <p className="font-mono text-xs text-rose-400 tracking-[.4em] uppercase font-bold">ĐỢT 01 BẮT ĐẦU</p>
                <h2 className="font-display text-5xl sm:text-6xl font-extrabold text-cyan drop-shadow-[0_0_40px_rgba(85,244,255,1)]">
                  XUẤT TRẬN!
                </h2>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Cinematic Wave Transition Overlay */}
      {waveTransition.active && !introState.active && hyperBeamPhase === "idle" && (
        <div className="pointer-events-none absolute inset-0 z-30 flex flex-col items-center justify-center bg-black/50 backdrop-blur-sm transition-all duration-300">
          {waveTransition.phase === "cleared" && (
            <div className="text-center animate-pulse">
              <p className="font-mono text-xs text-[#ffc857] tracking-[.3em] uppercase">BẢO VỆ THÀNH CÔNG</p>
              <h2 className="font-display text-4xl sm:text-5xl font-extrabold text-cyan tracking-wider drop-shadow-[0_0_30px_rgba(85,244,255,0.8)]">
                VƯỢT QUA ĐỢT {waveTransition.clearedWave.toString().padStart(2, "0")}
              </h2>
            </div>
          )}

          {waveTransition.phase === "warp" && (
            <div className="text-center animate-bounce">
              <p className="font-mono text-xs text-cyan tracking-[.4em] uppercase">ĐỢT MỚI ĐANG TỚI</p>
              <h2 className="font-display text-3xl sm:text-4xl font-extrabold text-white tracking-widest drop-shadow-[0_0_40px_rgba(255,255,255,0.9)]">
                »» TIẾN VÀO KHU VỰC {waveTransition.incomingWave.toString().padStart(2, "0")} ««
              </h2>
            </div>
          )}

          {waveTransition.phase === "incoming" && (
            <div className="text-center">
              {waveTransition.isBoss ? (
                <div className="animate-pulse">
                  <p className="font-mono text-xs text-rose-400 tracking-[.35em] uppercase font-bold"><i className="fa-solid fa-triangle-exclamation" aria-hidden="true" /> MỨC ĐỘ NGUY HIỂM TỐI CAO <i className="fa-solid fa-triangle-exclamation" aria-hidden="true" /></p>
                  <h2 className="font-display text-4xl sm:text-5xl font-extrabold text-rose-500 tracking-wider drop-shadow-[0_0_40px_rgba(255,51,102,0.9)]">
                    TRÙM XUẤT HIỆN
                  </h2>
                  <p className="font-mono text-xs text-rose-200 mt-1">GÕ ĐÚNG ĐÁP ÁN {bossHpForWave(waveTransition.incomingWave)} LẦN ĐỂ HẠ TRÙM</p>
                </div>
              ) : (
                <div className="animate-pulse">
                  <p className="font-mono text-xs text-cyan tracking-[.3em] uppercase">PHÁT HIỆN ĐỘI MÁY BAY ĐỊCH MỚI</p>
                  <h2 className="font-display text-4xl sm:text-5xl font-extrabold text-cyan tracking-wider drop-shadow-[0_0_30px_rgba(85,244,255,0.8)]">
                    ĐỢT {waveTransition.incomingWave.toString().padStart(2, "0")} ĐANG TỚI
                  </h2>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* Boss Inbound Alert Banner */}
      {inboundBoss && !waveTransition.active && !introState.active && (
        <div className="pointer-events-none absolute top-4 left-1/2 -translate-x-1/2 bg-rose-950/85 border border-rose-500 px-5 py-1.5 rounded-full backdrop-blur z-20 animate-bounce shadow-[0_0_24px_rgba(255,51,102,0.8)]">
          <p className="font-mono text-xs font-bold text-rose-300 tracking-widest uppercase">
            <i className="fa-solid fa-triangle-exclamation" aria-hidden="true" /> CẢNH BÁO: TRÙM ĐANG TỚI <i className="fa-solid fa-triangle-exclamation" aria-hidden="true" />
          </p>
        </div>
      )}
    </div>
  );
};
