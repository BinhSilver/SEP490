import { SoundManager as AirSound } from './game/soundEffects';
import { SoundManager as CatSound } from '../../neko-catch/src/audio/SoundManager';
import { setAssetBase } from '../../neko-catch/src/assets';
import * as memory from '../../memory/src/sfx';

export type PreviewGame = 'air' | 'cat' | 'memory';

// Separate instances in the preview iframe; never write the player's audio preferences.
export class GameEffectsAudio {
  private air: AirSound | null = null;
  private cat: CatSound | null = null;
  private enabled = false;
  private readonly volume = 0.6;
  private game: PreviewGame = 'air';

  private airSound() {
    if (!this.air) {
      this.air = new AirSound(false);
      this.air.updateSettings({ masterEnabled: true, masterVolume: this.volume * 100 });
    }
    return this.air;
  }

  private catSound() {
    if (!this.cat) {
      setAssetBase('/arena/app/neko/');
      this.cat = new CatSound(false);
      this.cat.setConfig({ muted: false, bgmVolume: 0, sfxVolume: 0.7 * this.volume });
    }
    return this.cat;
  }

  setEnabled(enabled: boolean) {
    this.enabled = enabled;
    if (!enabled) this.stop();
    else this.unlock();
  }

  unlock() {
    if (!this.enabled) return;
    if (this.game === 'air') this.airSound().unlock();
    if (this.game === 'cat') this.catSound().unlock();
    if (this.game === 'memory') { memory.setMemoryVolume(this.volume); memory.unlockMemoryAudio(); }
  }

  setGame(game: PreviewGame) {
    this.stop();
    this.game = game;
    this.unlock();
  }

  restartScene() {
    this.stop();
    this.unlock();
  }

  stop() {
    this.air?.dispose(); this.air = null;
    this.cat?.dispose(); this.cat = null;
    memory.stopMemoryAudio();
  }

  playEffect(game: PreviewGame, action: string) {
    if (!this.enabled || game !== this.game) return;
    if (game === 'air') {
      const air = this.airSound();
      const sounds: Record<string, () => void> = {
        fire: () => air.playKeyStroke(), impact: () => air.playLaser(), explode: () => air.playExplosion(),
        charge: () => air.playHyperBeamCharge(), beam: () => air.playHyperBeam(), hit: () => air.playDamage(),
        warp: () => air.playWarpDrive(), boss: () => air.playBossSiren(),
        pickup_credit: () => air.playItemCollect('CREDIT_CRYSTAL'),
        pickup_repair: () => air.playItemCollect('REPAIR_PACK'),
        pickup_energy: () => air.playItemCollect('HYPER_ORB'),
        miss: () => air.playComboBreak(), shield_block: () => air.playDamage(), defeat: () => air.playExplosion(),
        boss_hit: () => air.playLaser(), boss_explode: () => air.playExplosion(),
        boss_alert: () => air.playBossSiren(), boss_wave: () => air.playBossSiren(),
        intro_boot: () => air.playIntroLaunch(), intro_warp: () => air.playWarpDrive(), intro_ready: () => air.playIntroLaunch(),
        wave_clear: () => air.playWaveClear(), wave_incoming: () => air.playWarpDrive(),
        combo_gain: () => air.playComboDing(3), combo_break: () => air.playComboBreak(),
        bgm_lobby: () => air.switchBgm('lobby'), bgm_battle: () => air.switchBgm('battle'), bgm_boss: () => air.switchBgm('boss')
      };
      if (/^combo(?:5|10|20|50)$/.test(action)) air.playComboMilestone(Number(action.slice(5)));
      else sounds[action]?.();
    } else if (game === 'cat') {
      const id = ({ catch_success: 'correct', catch_wrong: 'wrong', missed: 'wrong', win: 'victory', lose: 'wrong', shield_block: 'levelup',
        combo3: 'levelup', combo6: 'levelup', combo9: 'levelup', pickup_golden_fish: 'levelup', pickup_clock: 'levelup',
        pickup_shield: 'levelup', pickup_freeze: 'freeze', freeze: 'freeze', countdown: 'countdown', go: 'countdown', paused: 'click' } as Record<string, string>)[action];
      if (id) this.catSound().playSFX(id);
      if (action === 'catch_wrong' || action === 'missed') this.catSound().playSFX('impact');
      if (action === 'bgm') { this.catSound().setConfig({ bgmVolume: this.volume * .4 }); this.catSound().playBGM(); }
    } else {
      memory.setMemoryVolume(this.volume);
      const sounds: Record<string, () => void> = {
        flip: memory.playFlipSfx, match: () => memory.playMatchSfx(1), mismatch: memory.playMismatchSfx,
        combo2: () => memory.playMatchSfx(2), combo5: () => memory.playMatchSfx(5),
        complete: memory.playCompleteSfx, win: memory.playCompleteSfx,
        click: memory.playClickSfx, paused: memory.playClickSfx, bgm: memory.startBackgroundMusic
      };
      if (/^countdown[0-3]$/.test(action)) { memory.playCountdownSfx(Number(action.slice(-1))); return; }
      sounds[action]?.();
    }
  }
}
