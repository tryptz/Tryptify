# Translation glossary

Fixed terms for `strings.tsv`. A term that appears in more than one string —
a tab name, a settings path, a recurring action — must read the same
everywhere, or the app sends people looking for a word that is not on screen.
Add a row here before using a new recurring term, and reuse the row after.

Languages: Simplified Chinese (zh-CN), Japanese, French, Spanish (neutral,
not Spain-only), Turkish, German, Italian (it).

## Register

| Language | Address | Notes |
|---|---|---|
| zh-CN | neutral, no 您/你 where avoidable | Full-width punctuation （），、：“” around Chinese text. Space between Chinese and Latin/numbers (`导出 CSV`). |
| ja | です/ます for sentences, noun phrases for labels | 「」 for UI names. No space between Japanese and Latin except around product names. |
| fr | *vous* | Typographic apostrophe ’. A space before : ; ? ! — non-breaking; the generator turns a typed space into U+00A0, so a mark never wraps onto its own line. Guillemets « » with non-breaking spaces inside. |
| es | *tú* | Neutral Latin American / Spain vocabulary: *playlist*, *canción*, *ajustes*. |
| tr | *siz* (formal plural) in sentences, imperative in buttons | Never pluralise after a number (“5 parça”, not “5 parçalar”). Suffixes after names take an apostrophe: Spotify’ı. |
| de | *du* | „Anführungszeichen“ for quoted UI names. Nouns capitalised; compound words joined or hyphenated (App-Sprache). |
| it | *tu* | Straight apostrophe ' (as in the rest of the column). «Caporali» around quoted UI names. Buttons use the infinitive or the imperative (*Salva*, *Annulla*). |

## This app's own UI

| English | zh-CN | ja | fr | es | tr | de | it |
|---|---|---|---|---|---|---|---|
| Home (tab) | 首页 | ホーム | Accueil | Inicio | Ana Sayfa | Start | Home |
| Discover (tab) | 发现 | 見つける | Découvrir | Descubrir | Keşfet | Entdecken | Scopri |
| Radio (tab) | 电台 | ラジオ | Radio | Radio | Radyo | Radio | Radio |
| Library (tab) | 音乐库 | ライブラリ | Bibliothèque | Biblioteca | Kitaplık | Bibliothek | Libreria |
| Search (tab) | 搜索 | 検索 | Rechercher | Buscar | Ara | Suche | Cerca |
| Settings | 设置 | 設定 | Paramètres | Ajustes | Ayarlar | Einstellungen | Impostazioni |
| Settings › System | 设置 › 系统 | 設定 › システム | Paramètres › Système | Ajustes › Sistema | Ayarlar › Sistem | Einstellungen › System | Impostazioni › Sistema |
| Settings › Appearance | 设置 › 外观 | 設定 › 外観 | Paramètres › Apparence | Ajustes › Apariencia | Ayarlar › Görünüm | Einstellungen › Darstellung | Impostazioni › Aspetto |
| Settings › System › Performance | 设置 › 系统 › 性能 | 設定 › システム › パフォーマンス | Paramètres › Système › Performances | Ajustes › Sistema › Rendimiento | Ayarlar › Sistem › Performans | Einstellungen › System › Leistung | Impostazioni › Sistema › Prestazioni |
| Frame rate limit / FPS counter | 帧率上限 / 帧率计数器 | フレームレートの上限 / FPS カウンター | Limite de fréquence d’images / Compteur de FPS | Límite de frecuencia de fotogramas / Contador de FPS | Kare hızı sınırı / FPS sayacı | Bildratenbegrenzung / FPS-Zähler | Limite frame rate / Contatore FPS |
| Settings tabs: Appearance · Fonts · Theme Store · Visual Studio · Audio · Equalizer · Library · Downloads · Connections · Radio · System · About | 外观 · 字体 · 主题商店 · 视觉工作室 · 音频 · 均衡器 · 音乐库 · 下载 · 连接 · 电台 · 系统 · 关于 | 外観 · フォント · テーマストア · ビジュアルスタジオ · オーディオ · イコライザ · ライブラリ · ダウンロード · 接続 · ラジオ · システム · 情報 | Apparence · Polices · Boutique de thèmes · Studio visuel · Audio · Égaliseur · Bibliothèque · Téléchargements · Connexions · Radio · Système · À propos | Apariencia · Fuentes · Tienda de temas · Estudio visual · Audio · Ecualizador · Biblioteca · Descargas · Conexiones · Radio · Sistema · Acerca de | Görünüm · Yazı tipleri · Tema Mağazası · Görsel Stüdyo · Ses · Ekolayzer · Kitaplık · İndirilenler · Bağlantılar · Radyo · Sistem · Hakkında | Darstellung · Schriftarten · Theme-Store · Visual Studio · Audio · Equalizer · Bibliothek · Downloads · Verbindungen · Radio · System · Info | Aspetto · Caratteri · Negozio di temi · Studio visivo · Audio · Equalizzatore · Libreria · Download · Connessioni · Radio · Sistema · Info |
| APIs (Connections) | API | API | API | API | API’ler | APIs | API |
| playlist | 歌单 | プレイリスト | playlist | playlist | çalma listesi | Playlist | playlist |
| track / song | 歌曲 | 曲 | titre | canción | parça | Titel | brano |
| queue | 播放队列 | キュー | file d’attente | cola | sıra | Warteschlange | coda |
| like / unlike | 喜欢 / 取消喜欢 | お気に入りに追加 / お気に入りから削除 | J’aime / Je n’aime plus | Me gusta / Ya no me gusta | Beğen / Beğenmekten vazgeç | Gefällt mir / Gefällt mir nicht mehr | Mi piace / Non mi piace più |
| Now playing | 正在播放 | 再生中 | En cours de lecture | Reproduciendo | Şimdi çalıyor | Wird gespielt | In riproduzione |
| listening party | 听歌派对 | リスニングパーティー | soirée d’écoute | fiesta de escucha | dinleme partisi | Hörparty | festa d'ascolto |
| host (of a party) | 主持人 | ホスト | hôte | anfitrión | ev sahibi | Gastgeber | host |
| guest (on the party website) | 访客 | ゲスト | invité | invitado | misafir | Gast | ospite |
| Up next (a party's queue) | 接下来 | 次に再生 | À suivre | A continuación | Sıradakiler | Als Nächstes | In coda |
| nav bar (Home · … · Library, Search) | 导航栏 | ナビゲーションバー | barre de navigation | barra de navegación | gezinme çubuğu | Navigationsleiste | barra di navigazione |
| Liquid glass | 液态玻璃 | リキッドグラス | verre liquide | cristal líquido | sıvı cam | Liquid Glass | liquid glass |
| preset | 预设 | プリセット | préréglage | preset | ön ayar | Preset | preset |
| Music links (screen) / a link | 音乐联动 / 联动 | ミュージックリンク / リンク | Liens musicaux / lien | Enlaces musicales / enlace | Müzik bağlantıları / bağlantı | Musik-Kopplungen / Kopplung | Collegamenti musicali / collegamento |
| Move with the music (action) | 随音乐而动 | 音楽に合わせて動かす | Bouger avec la musique | Mover con la música | Müzikle hareket ettir | Mit der Musik bewegen | Muoviti con la musica |
| Theme Store (screen) / theme (a pack in it) | 主题商店 / 主题 | テーマストア / テーマ | Boutique de thèmes / thème | Tienda de temas / tema | Tema Mağazası / tema | Theme-Store / Theme | Negozio di temi / tema |
| Share my look (action) | 分享我的外观 | 自分の外観を共有 | Partager mon look | Compartir mi estilo | Görünümümü paylaş | Meinen Look teilen | Condividi il mio look |
| Share code (label) / code (a theme's, in sentences) | 分享码 | 共有コード / コード | Code de partage / code | Código para compartir / código | Paylaşım kodu / kod | Teilcode / Code | Codice di condivisione / codice |
| CRT effect (Settings › Appearance) | CRT 效果 | CRT エフェクト | Effet CRT | Efecto CRT | CRT efekti | CRT-Effekt | Effetto CRT |
| Bluetooth sync (Settings › Audio) | 蓝牙同步 | Bluetooth 同期 | Synchro Bluetooth | Sincronización Bluetooth | Bluetooth senkronizasyonu | Bluetooth-Synchronisierung | Sincronizzazione Bluetooth |
| Library galaxy | 音乐库星系 | ライブラリ銀河 | Galaxie de la bibliothèque | Galaxia de la biblioteca | Kitaplık galaksisi | Bibliotheks-Galaxie | Galassia della libreria |
| Achievements (stats tab) | 成就 | 実績 | Succès | Logros | Başarılar | Erfolge | Obiettivi |
| Multiplayer (Trypt Mania) | 多人游戏 | マルチプレイ | Multijoueur | Multijugador | Çok oyunculu | Mehrspieler | Multigiocatore |
| room (Trypt Mania multiplayer) | 房间 | ルーム | salon | sala | oda | Raum | stanza |
| token (Discord, ListenBrainz) | 令牌 | トークン | jeton | token | belirteç | Token | token |
| scrobble | 同步收听记录（scrobble） | スクロブル | scrobbler | scrobbling | scrobble | scrobbeln | scrobbling |
| My server (Library page) / music server (a Subsonic server: Navidrome, Gonic…) | 我的服务器 / 音乐服务器 | マイサーバー / 音楽サーバー | Mon serveur / serveur musical | Mi servidor / servidor de música | Sunucum / müzik sunucusu | Mein Server / Musikserver | Il mio server / server musicale |

## Other apps' UI, quoted in instructions

Name a menu as the person's own device shows it in their language.

| English | zh-CN | ja | fr | es | tr | de | it |
|---|---|---|---|---|---|---|---|
| Music app (Mac) | “音乐”App | 「ミュージック」App | l’app Musique | la app Música | Müzik uygulaması | Musik-App | l'app Musica |
| File › Library › Export Playlist | 文件 › 资料库 › 导出播放列表 | ファイル › ライブラリ › プレイリストを書き出す | Fichier › Bibliothèque › Exporter la playlist | Archivo › Biblioteca › Exportar playlist | Dosya › Arşiv › Çalma Listesini Dışa Aktar *(unverified on a Turkish Mac)* | Ablage › Mediathek › Playlist exportieren | File › Libreria › Esporta playlist |
| Format: Text | 文本 | テキスト | Texte | Texto | Metin | Text | Testo |
| Developer options › Disable USB audio routing (Android) | 开发者选项 › 停用 USB 音频路由 | 開発者向けオプション › USB オーディオ ルーティングを無効化 | Options pour les développeurs › Désactiver routage audio USB | Opciones para desarrolladores › Inhabilitar enrutamiento audio USB | Geliştirici seçenekleri › USB ses yönlendirmesini devre dışı bırak | Entwickleroptionen › USB-Audiorouting deaktivieren | Opzioni sviluppatore › Disattiva routing audio USB |
| Developer Mode / Copy Channel ID (Discord) | 开发者模式 / 复制频道 ID | 開発者モード / チャンネルIDをコピー | Mode développeur / Copier l’identifiant du salon | Modo desarrollador / Copiar ID del canal | Geliştirici Modu / Kanal ID’sini Kopyala | Entwicklermodus / Kanal-ID kopieren | Modalità sviluppatore / Copia ID canale |
| Blend modes: Normal · Screen · Additive · Soft Light (image editors) | 正常 · 滤色 · 相加 · 柔光 | 通常 · スクリーン · 加算 · ソフトライト | Normal · Superposition · Addition · Lumière tamisée | Normal · Trama · Aditivo · Luz suave | Normal · Ekran · Toplama · Yumuşak ışık | Normal · Negativ multiplizieren · Addieren · Weiches Licht | Normale · Scherma · Additivo · Luce soffusa |
| Google Takeout | Google Takeout | Google データエクスポート（Takeout） | Google Takeout | Google Takeout | Google Takeout | Google Takeout | Google Takeout |
| Equalizer → Advanced (Nothing X) | 均衡器 → 高级 | イコライザー → 詳細 | Égaliseur → Avancé | Ecualizador → Avanzado | Ekolayzer → Gelişmiş | Equalizer → Erweitert *(all unverified against Nothing X)* | Equalizzatore → Avanzate |

## Never translated

Brand and format names: Tryptify, Spotify, Apple Music, YouTube Music,
YouTube, SoundCloud, TuneMyMusic, exportify.net and Exportify, THX (but “Spatial Audio” after it
is), AutoEQ, CSV, JSPF, XSPF, XML, FLAC, projectM, MilkDrop, Wave Candy,
Oxford, Nothing Ear, Nothing X.

Trypt Mania, the rhythm game, is a name in every language. Its own row
(`trypt_mania`) is in `NON_TRANSLATABLE` in `generate_strings.py`, so it has
no translations; strings that name it keep it as it is (“Trypt Mania でプレイ”).

CRT stays CRT, and CRT-Royale, the one look named after its shader, stays
CRT-Royale.

Plugin and console vocabulary stays English, as in every DAW, localized
hosts included: Oxford's knob labels (INPUT, CURVE, THRESH, RATIO, ATTACK,
RELEASE, MAKEUP, GR…), mixer plugin parameter names (`ParamDefs.kt`), factory
preset names (`FxPresets.kt`, WARMTH, PUNCH, DRUM BUS…), EQ filter types
(PEAK, LOW-S, HIGH-S) and the channel strip's short labels (M, fx, -inf, MIX,
OS, IN/OUT, LFE). Help text that names them uses them as they appear.

Also kept English on purpose: What's New, the debug log, and data the app
stores (an imported preset's description, a bus's name).
