# Translation glossary

Fixed terms for `strings.tsv`. A term that appears in more than one string —
a tab name, a settings path, a recurring action — must read the same
everywhere, or the app sends people looking for a word that is not on screen.
Add a row here before using a new recurring term, and reuse the row after.

Languages: Simplified Chinese (zh-CN), Japanese, French, Spanish (neutral,
not Spain-only), Turkish, German.

## Register

| Language | Address | Notes |
|---|---|---|
| zh-CN | neutral, no 您/你 where avoidable | Full-width punctuation （），、：“” around Chinese text. Space between Chinese and Latin/numbers (`导出 CSV`). |
| ja | です/ます for sentences, noun phrases for labels | 「」 for UI names. No space between Japanese and Latin except around product names. |
| fr | *vous* | Typographic apostrophe ’. A space before : ; ? ! — non-breaking; the generator turns a typed space into U+00A0, so a mark never wraps onto its own line. Guillemets « » with non-breaking spaces inside. |
| es | *tú* | Neutral Latin American / Spain vocabulary: *playlist*, *canción*, *ajustes*. |
| tr | *siz* (formal plural) in sentences, imperative in buttons | Never pluralise after a number (“5 parça”, not “5 parçalar”). Suffixes after names take an apostrophe: Spotify’ı. |
| de | *du* | „Anführungszeichen“ for quoted UI names. Nouns capitalised; compound words joined or hyphenated (App-Sprache). |

## This app's own UI

| English | zh-CN | ja | fr | es | tr | de |
|---|---|---|---|---|---|---|
| Home (tab) | 首页 | ホーム | Accueil | Inicio | Ana Sayfa | Start |
| Discover (tab) | 发现 | 見つける | Découvrir | Descubrir | Keşfet | Entdecken |
| Radio (tab) | 电台 | ラジオ | Radio | Radio | Radyo | Radio |
| Library (tab) | 音乐库 | ライブラリ | Bibliothèque | Biblioteca | Kitaplık | Bibliothek |
| Search (tab) | 搜索 | 検索 | Rechercher | Buscar | Ara | Suche |
| Settings | 设置 | 設定 | Paramètres | Ajustes | Ayarlar | Einstellungen |
| Settings › System | 设置 › 系统 | 設定 › システム | Paramètres › Système | Ajustes › Sistema | Ayarlar › Sistem | Einstellungen › System |
| Settings › Appearance | 设置 › 外观 | 設定 › 外観 | Paramètres › Apparence | Ajustes › Apariencia | Ayarlar › Görünüm | Einstellungen › Darstellung |
| Settings tabs: Appearance · Visual Studio · Audio · Equalizer · Library · Downloads · Connections · Radio · System · About | 外观 · 视觉工作室 · 音频 · 均衡器 · 音乐库 · 下载 · 连接 · 电台 · 系统 · 关于 | 外観 · ビジュアルスタジオ · オーディオ · イコライザ · ライブラリ · ダウンロード · 接続 · ラジオ · システム · 情報 | Apparence · Studio visuel · Audio · Égaliseur · Bibliothèque · Téléchargements · Connexions · Radio · Système · À propos | Apariencia · Estudio visual · Audio · Ecualizador · Biblioteca · Descargas · Conexiones · Radio · Sistema · Acerca de | Görünüm · Görsel Stüdyo · Ses · Ekolayzer · Kitaplık · İndirilenler · Bağlantılar · Radyo · Sistem · Hakkında | Darstellung · Visual Studio · Audio · Equalizer · Bibliothek · Downloads · Verbindungen · Radio · System · Info |
| APIs (Connections) | API | API | API | API | API’ler | APIs |
| playlist | 歌单 | プレイリスト | playlist | playlist | çalma listesi | Playlist |
| track / song | 歌曲 | 曲 | titre | canción | parça | Titel |
| queue | 播放队列 | キュー | file d’attente | cola | sıra | Warteschlange |
| like / unlike | 喜欢 / 取消喜欢 | お気に入りに追加 / お気に入りから削除 | J’aime / Je n’aime plus | Me gusta / Ya no me gusta | Beğen / Beğenmekten vazgeç | Gefällt mir / Gefällt mir nicht mehr |
| Now playing | 正在播放 | 再生中 | En cours de lecture | Reproduciendo | Şimdi çalıyor | Wird gespielt |
| Liquid glass | 液态玻璃 | リキッドグラス | verre liquide | cristal líquido | sıvı cam | Liquid Glass |
| preset | 预设 | プリセット | préréglage | preset | ön ayar | Preset |
| token (Discord, ListenBrainz) | 令牌 | トークン | jeton | token | belirteç | Token |
| scrobble | 同步收听记录（scrobble） | スクロブル | scrobbler | scrobbling | scrobble | scrobbeln |

## Other apps' UI, quoted in instructions

Name a menu as the person's own device shows it in their language.

| English | zh-CN | ja | fr | es | tr | de |
|---|---|---|---|---|---|---|
| Music app (Mac) | “音乐”App | 「ミュージック」App | l’app Musique | la app Música | Müzik uygulaması | Musik-App |
| File › Library › Export Playlist | 文件 › 资料库 › 导出播放列表 | ファイル › ライブラリ › プレイリストを書き出す | Fichier › Bibliothèque › Exporter la playlist | Archivo › Biblioteca › Exportar playlist | Dosya › Arşiv › Çalma Listesini Dışa Aktar *(unverified on a Turkish Mac)* | Ablage › Mediathek › Playlist exportieren |
| Format: Text | 文本 | テキスト | Texte | Texto | Metin | Text |
| Developer options › Disable USB audio routing (Android) | 开发者选项 › 停用 USB 音频路由 | 開発者向けオプション › USB オーディオ ルーティングを無効化 | Options pour les développeurs › Désactiver routage audio USB | Opciones para desarrolladores › Inhabilitar enrutamiento audio USB | Geliştirici seçenekleri › USB ses yönlendirmesini devre dışı bırak | Entwickleroptionen › USB-Audiorouting deaktivieren |
| Developer Mode / Copy Channel ID (Discord) | 开发者模式 / 复制频道 ID | 開発者モード / チャンネルIDをコピー | Mode développeur / Copier l’identifiant du salon | Modo desarrollador / Copiar ID del canal | Geliştirici Modu / Kanal ID’sini Kopyala | Entwicklermodus / Kanal-ID kopieren |
| Blend modes: Normal · Screen · Additive · Soft Light (image editors) | 正常 · 滤色 · 相加 · 柔光 | 通常 · スクリーン · 加算 · ソフトライト | Normal · Superposition · Addition · Lumière tamisée | Normal · Trama · Aditivo · Luz suave | Normal · Ekran · Toplama · Yumuşak ışık | Normal · Negativ multiplizieren · Addieren · Weiches Licht |
| Google Takeout | Google Takeout | Google データエクスポート（Takeout） | Google Takeout | Google Takeout | Google Takeout | Google Takeout |

## Never translated

Brand and format names: Tryptify, Spotify, Apple Music, YouTube Music,
TuneMyMusic, exportify.net, THX (but “Spatial Audio” after it is), AutoEQ,
CSV, JSPF, XSPF, XML, FLAC, projectM, MilkDrop, Wave Candy, Oxford.

Plugin and console vocabulary stays English, as in every DAW, localized
hosts included: Oxford's knob labels (INPUT, CURVE, THRESH, RATIO, ATTACK,
RELEASE, MAKEUP, GR…), mixer plugin parameter names (`ParamDefs.kt`), factory
preset names (`FxPresets.kt`, WARMTH, PUNCH, DRUM BUS…), EQ filter types
(PEAK, LOW-S, HIGH-S) and the channel strip's short labels (M, fx, -inf, MIX,
OS, IN/OUT, LFE). Help text that names them uses them as they appear.

Also kept English on purpose: What's New, the debug log, and data the app
stores (an imported preset's description, a bus's name).
