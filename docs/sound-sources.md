# clay-forge 音效来源（v0.1.1）

全部是 CC0 真实录音，没有合成音。处理：ffmpeg 去前导静音、截短、淡出、单声道 44.1 kHz、峰值归一到 −1 dBFS、Ogg Vorbis q5（参数写在下面）。播放增益 = 设计包 `assets.sound`（−8 … −14 dB）+ 8 dB（响度门禁，deviation）× 设置音量 / 65 %，随机音高 ±3%，同类一次只放一个。2026-11-11 逐页核过「Creative Commons 0」。

| 文件 (res/raw) | 用在 | 来源 | 作者 | 处理 |
|---|---|---|---|---|
| squish_1.ogg | 加泥（每 1.5 cm 路径一声）、戳一下 | https://freesound.org/people/Breviceps/sounds/445109/ （Mud Splat） | Breviceps | 0.35 s，低通 4 kHz，+4.8 dB |
| squish_2.ogg | 同上（轮换） | https://freesound.org/people/Breviceps/sounds/445117/ （Cartoon Splat） | Breviceps | 0.35 s，低通 3 kHz，+3.5 dB |
| squish_3.ogg | 同上（轮换） | https://freesound.org/people/Breviceps/sounds/445118/ （Cartoon - Splat!） | Breviceps | 0.35 s，低通 3 kHz，−0.7 dB |
| scrape.ogg | 挖 / 抹平（拖动中循环，抹平音高 −20%、−4 dB），松手停 | https://freesound.org/people/be_a_hero_not_a_patriot/sounds/460803/ （trowel, mortar, clay and sand, scrape off） | be_a_hero_not_a_patriot | 0.6 s，+27.1 dB（原录音很轻） |
| wood_tap_1.ogg | 拿起 / 放下刀、换大小、[转 90°] 到位、导出 | Kenney Impact Sounds（CC0）impactWood_light_000.ogg | Kenney | 0.3 s，+1.3 dB |
| wood_tap_2.ogg | 同上（轮换） | Kenney Impact Sounds（CC0）impactWood_light_001.ogg | Kenney | 0.3 s，+2.4 dB |
| wood_tap_3.ogg | 同上（轮换） | Kenney Impact Sounds（CC0）impactWood_light_002.ogg | Kenney | 0.3 s，+0.7 dB |
| jar_clink_1.ogg | 点釉罐 | https://freesound.org/people/Nightflame/sounds/368613/ （cup_02.wav） | Nightflame | 0.4 s，+3.1 dB |
| jar_clink_2.ogg | 点釉罐（轮换） | https://freesound.org/people/Nightflame/sounds/368614/ （cup_01.wav） | Nightflame | 0.4 s，−0.8 dB |
| kiln_roar.ogg | 窑门关好后的火声（烧 1.4 s） | https://freesound.org/people/PegasusCZ/sounds/569332/ （Gas burner 01.wav） | PegasusCZ | 1.4 s，低通 2.5 kHz，+4.0 dB |
| kiln_ting.ogg | 开门后转盘出窑时一声瓷器响 | https://freesound.org/people/FishPlinko/sounds/756421/ （Ceramic Lid） | FishPlinko | 0.9 s，+1.2 dB |
| kiln_latch.ogg | 窑门关上的门闩「咔」（进窑后、出窑后各一次） | Kenney Impact Sounds（CC0）impactMetal_medium_000.ogg | Kenney | 0.35 s，尾部淡出 0.1 s，−1.2 dBFS 峰值 |

开门声复用 wood_tap（音高 −20%、−14 dB）。示范课里刀自己动时用同样的声音、整体 −3 dB（设计包 lessons.watch.sound）。

拉刀无声（设计包 soundRules：v2 再加）。没有环境底噪（B24）。
