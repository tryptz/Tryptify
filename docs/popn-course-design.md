# pop'n music as a course type in Trypt Mania

Trypt Mania began as a four-panel dance game: a song is separated into stems on
a server, the drums set the grid, the tune and the bass place arrows on it, and
the engine judges the arrows on four lanes. This document brings pop'n music
into that design as a second **course type**: the same stems, the same grid,
the same engine and scoring, with pop'n's nine buttons in place of the four
panels. A toggle switches between the two.

It is written in two halves. The first is the research: what pop'n music is,
as precisely as public sources allow, so the mode is built on the real thing
rather than a memory of it. The second is the engineering: how the course type
threads through the generator, the files, the engine, the field and the
settings, what was decided and why, and what is deliberately left for later.

## Part one: what pop'n music is

### The buttons

The cabinet has nine large buttons in two rows: **five below and four above**,
the upper four each sitting over the gap between two lower ones. Read left to
right across both rows they are numbered 1 to 9:

| Button | Colour | Row | StepMania's name |
|---|---|---|---|
| 1 | white | bottom | Left White |
| 2 | yellow | top | Left Yellow |
| 3 | green | bottom | Left Green |
| 4 | blue | top | Left Blue |
| 5 | **red** | bottom | Red |
| 6 | blue | top | Right Blue |
| 7 | green | bottom | Right Green |
| 8 | yellow | top | Right Yellow |
| 9 | white | bottom | Right White |

The layout is symmetric about the red centre, and the colours mirror: the odd
buttons (white, green, red, green, white) are the bottom row, the even ones
(yellow, blue, blue, yellow) the top. The notes, called **pop-kun**, fall in
nine columns in the same order and in the same colour as the button they land
on, so colour carries *which button*, where a dance arrow's colour carries
*which beat*. StepMania's `GameManager.cpp` names the buttons exactly as the
table above and lays the nine columns out at a constant spacing, left to right.

### Modes, tiers and levels

Every song has a **NORMAL** chart on all nine buttons; most have **HYPER**
(harder) and many **EX** (hardest). pop'n rates charts **1 to 50** (it was 1 to
43 before *Sunny Park*); the tiers overlap, roughly NORMAL in the low twenties
and up to 37, HYPER from the teens to the high forties, EX from 29 to 50.
Below NORMAL the game had a **5-Buttons** mode from pop'n music 5 to 20, which
uses only the **centre five** buttons (3 to 7: green, blue, red, blue, green);
*Sunny Park* replaced it with **EASY**, whose charts use anywhere from three to
nine buttons. Difficulty is changed on the song wheel by pressing both yellow
buttons.

### Judgements and timing

A note is judged **COOL**, **GREAT**, **GOOD** or **BAD**. COOL is the tight
centre of GREAT and was once only on in course and expert modes; it has been on
always since pop'n music 20 and the portable releases. A BAD is a note hit too
late or not at all, and a press with nothing to hit is also a BAD (a "空BAD",
an empty BAD, in the Japanese community's words).

Konami has never published the windows. The best public figures:

* COOL is one or two frames at 60 Hz: a Japanese fan wiki says "two frames";
  the pop'n music 8 official Q&A said COOL matches beatmania IIDX's PGREAT,
  which is ±16.67 ms; one player's measurement of the current arcade release
  puts it at 25 ms, the same for every song.
* GREAT and GOOD have no published width. The common assumption is that each
  is about double the one inside it, which would put GREAT near ±50 ms and
  GOOD near ±100 ms; some songs are known to have a narrower GREAT or GOOD
  ("辛判定", a harsh judgement), and the console releases differed again.
* The judgement window widened in pop'n music 15 to allow for LCD delay.

These are not certain enough to code as pop'n's windows, and the mode does not
pretend to: it judges a pop'n chart with its StepMania windows (Marvelous
22.5 ms, Perfect 45, Great 90, Good 135, Boo 180, scaled by the Judge
setting), which are of the same order. A pop'n judgement set is listed below
under what is left for later.

### The groove gauge

The life bar is **24 segments** on screen; the song is cleared when **17 or
more** (about 70%) are filled at the end. Behind it the gauge runs 0 to 1024,
starting at 256 (six segments). A COOL or GREAT adds `3072 ÷ notes`, the
decimals dropped, so a chart with 1023 notes gains 3 a note and a short chart
gains a lot; a GOOD adds less; a BAD on a note takes 17, an empty or missed
BAD 51. From *Lapistoria* on a failed stage no longer ends the game. Gauge
types EASY, HARD and DANGER exist as options; a song with 1537 or more notes
uses a separate "spicy" gauge.

### Score, grades and medals

The score is out of **100,000**, each note worth `100,000 ÷ notes` (rounded
up). Since *Lapistoria* (2014) the ratio is **COOL 10 : GREAT 7 : GOOD 4 :
BAD 0**; before it, 10 : 5 : 1 : 0 (and with COOL off, GREAT full and GOOD
half). Grades, since *Usagi to Neko to Shounen no Yume*: **S** above 98,000,
**AAA** 95,000, **AA** 90,000, **A** 82,000, **B** 72,000, **C** 62,000,
**D** 50,000, **E** below; AA and up need a clear. A **full combo** (every
note GOOD or better, nothing missed) earns a silver medal, a **perfect** (all
GREAT, or all COOL and GREAT) a gold star.

### Long pop-kun

Hold notes, "long pop-kun", came with *Lapistoria*: a pop-kun with a bar
behind it in its colour, pressed at its head and held to its tail. The exact
rule for letting go early, and whether the tail counts as its own judgement,
is not in a public source this document could verify; the mode treats them as
it treats a dance freeze arrow (a head judgement, a tail that must be held).

### Options

**HI-SPEED** from 1.0 to 10.0 in tenths (it was 1 to 6 in halves until
*Lapistoria*); **RANDOM** (the columns shuffled), **MIRROR** (reflected about
the red button), **S-RANDOM** (every note on a random button); **HIDDEN**,
**SUDDEN** and their combination; and the **OJAMA** obstacles, which are
pop'n's own and out of scope. Trypt Mania's Turn, Appearance and speed
options stand in for these: Mirror reflects the nine columns, Shuffle is
RANDOM, Left and Right slide the columns one button round since a quarter
turn means nothing on nine buttons.

### Chart files

StepMania plays pop'n charts as its `popn` game: steps types **`pnm-nine`**
(nine columns) and **`pnm-five`** (five), the style names `popn-nine` and
`popn-five`. A `pnm-nine` chart in an .sm or .ssc is a `#NOTEDATA` block whose
`#STEPSTYPE` is `pnm-nine` and whose rows are nine characters wide, one a
column in the button order above; its five-button style maps the five columns
onto buttons 3 to 7. The pop'n simulators' own format is **PMS**, a BMS
variant from 1998 with the nine keys on channels 11 to 15 and 22 to 25;
StepMania reads it too. Trypt Mania writes and reads `pnm-nine` (and accepts
`popn-nine` as a spelling), since its whole chart path is StepMania's SSC.

### How pop'n charts are laid out

Chart authors describe pop'n patterns in a shared vocabulary that the
generator borrows from: **階段** (stairs: runs across adjacent buttons),
**交互** (alternation: left hand, right hand), **同時押し** (chords, very often
mirror pairs around the red button, 1+9, 2+8, 3+7, 4+6), **縦連** (jacks: the
same button twice running), **乱打** (scattered single hits) and **壁** (walls:
many buttons at once). NORMAL and HYPER charts follow the melody with stairs
and alternation and keep chords to the strong beats; EX charts add walls,
jacks and dense scatter.

## Part two: the engineering

### One analysis, two courses

Nothing changes on the server or in the analysis. The TryptMania Parser
servers return the drums' and each layer's envelopes, `StemRhythmAnalyzer`
turns them into one BPM, one offset and a sixteenth-note grid of strengths,
and `StemFeatureExtractor` adds the tune's onsets, its rough pitch contour,
the bass's onsets and how long each sung note is held. That `RhythmAnalysis`
is the input to both courses. A song charted for dance and then for pop'n is
separated twice only because the two are generated on two occasions; the
features it is read from are the same.

### The course type

`CourseType` (`audio/stepmania/CourseType.kt`) is the one place that knows a
course: its `#STEPSTYPE` and the other spellings it accepts, its lane count
(four or nine), the labels a toggle shows, the file suffix that keeps its
simfile apart, the `#METER` each tier is written with (dance keeps StepMania's
feet; pop'n writes 8, 14, 22, 32 and 40, inside pop'n's 1 to 50) and what each
tier is called on screen (pop'n's players know Beginner, Easy, Medium, Hard
and Challenge as Easy, 5-Buttons, Normal, Hyper and EX). It is
`@Serializable`, since a queued chart job carries it.

### The generator

`StepManiaMapGenerator` places notes through a `LanePlan`: the span of lanes
a tier may use, the order drum-led notes cycle through, the chords it strikes
on strong downbeats and how far the tune's pitch moves a note. Dance's plan is
exactly what the generator did before: two cycling orders, the two diagonal
jumps, one lane for a semitone and two for half an octave. pop'n's plan:

* **The span follows the tier.** Beginner stays on the centre three (blue,
  red, blue), Easy on the centre five, as the old 5-Buttons mode did; Normal,
  Hyper and EX use all nine.
* **Drum-led notes alternate hands outward from the red button**: red, the
  blues, the greens, the yellows, the whites, in one of two spirals chosen by
  the song's title, as the dance orders are.
* **Chords are mirror pairs** about the red button (4+6, 3+7, 2+8, 1+9), on
  strong downbeats, Hard and EX only, as dance's jumps are. No triples: a
  phone is played with two thumbs.
* **The tune steps across the buttons**: a semitone moves one button, a third
  two, a fifth three, an octave four, turning back at an edge; nine buttons
  give the tune the room the four panels did not have.
* **No jacks** between consecutive single notes, and nothing on a held
  button, the dance rule kept.
* **Holds** are the sung notes held long enough, as before; they become long
  pop-kun.

`StepEvent.laneMask` now holds up to nine bits; `StepChart` carries its
course; `SscWriter` writes `#STEPSTYPE:pnm-nine;`, the pop'n meter, and rows
nine characters wide. A request with no course set is a dance request, so
every existing caller and test is unchanged, and `PopnChartTest` pins the
rules above.

### Files: one simfile per course

A StepMania simfile may hold charts of several steps types, and the pack copy
written to the player's own folder does. In app storage, though, each course
has its own file: the dance chart keeps `<chartId>.ssc`, pop'n's is
`<chartId>.popn.ssc`. Generating one course never touches the other's file,
each course's chart state (none, unreadable, ready) is its own, and the
parser, the editor and the attempts store all key on the course without a
file holding two charts of one difficulty. `SscChartWriter.withOtherCourse`
joins the two into one simfile for the own pack; `withChart` matches a block
by difficulty *and* steps type, so an edit to one course's Medium leaves the
other's Medium alone.

`SscParser.parse(text, course)` reads one course's blocks (an unnamed
`#STEPSTYPE` is dance, as StepMania reads it). A pack's chart is parsed for
each course, so a pack of pop'n songs now imports and a pack with both plays
under both; the pack index records each song's pop'n tiers beside its dance
ones, and an index written before this fills them in the first time it is
read.

### The engine and the lanes

`GlyphLane` grew from four entries to nine. The first four keep their names
and ordinals (the native engine and every saved ghost carry ordinals), and
mean the first four buttons under pop'n; `GlyphPopnLane` says what each of
the nine is on a pop'n field: its button number, colour and row.
`GlyphLane.forCourse` is the lanes a course plays, and every loop that sized
itself from the enum now sizes from the chart's course. The native engine's
`kLaneCount` is nine for both courses, with the lanes of a chart checked when
it is created, since a lane past the arrays was a write past them on its
hold. The Kotlin twin, autoplay and the judgement path needed no rule
changes: a lane is a lane.

Turn is course-aware: Mirror reflects about the red button, Shuffle permutes
the nine, Left and Right slide them one round. Key bindings hold a set per
course, dance's as it was and pop'n's on A S D F, Space, J K L and the
semicolon with the number row behind; a saved line from before pop'n keeps
its dance keys.

### The field and the pad

The playfield splits its width by the chart's lane count, so a pop'n chart
is nine columns. pop'n's notes are not loaded from a noteskin: `GlyphPopnArt`
draws a pop-kun as a disc in its button's colour with a dark rim and a
highlight, a receptor as a ring that fills while its button is held, and a
long pop-kun as a bar of the colour. The dance skins, their manifests and the
tests that hold every skin image to something the field asks for are
untouched. One judgement line serves both rows, as StepMania draws it;
staggering the receptors by row is a possible later refinement.

The touch input is where the two courses differ most. A dance lane is tapped
anywhere down its column; nine such columns on a phone would each be a
thumb's width. pop'n is played on its buttons, so the pop'n input is the
**controller itself along the bottom**: two rows, the five odd buttons below
and the four even ones above, each over the gap between two below, each a
touch zone that draws its own button and lights while it is down. The field
above the pad is not touchable. The dance stage with its 3 × 3 pad is a
dance thing; pop'n gets a plainer stage behind the field.

### The toggle

The course is one setting for the whole mode, kept in Trypt Mania's
preferences (`course`) and shown in two places: a `GlyphSegmented` pill on a
line of its own above the difficulties on the song list, where switching
re-reads the selected song's charts of the other course at once, and a Course
type section under Settings › More beside Chart processing. The song list,
the selection, the editor, the keys, the high score tables and the leaderboard
all follow it. Generate charts the course that is on; a chart in play stays
what it is. The Settings search finds it under "pop'n" and "9 buttons".

### What is deliberately left for later

* **pop'n's own judgements** (COOL, GREAT, GOOD, BAD), the 24-segment groove
  gauge with its clear line at 17, the 100,000-point score and its medals.
  The windows are not published, and the StepMania scoring the mode already
  has ranks a pop'n run fairly against other pop'n runs. These would be a
  `JudgementStyle` alongside the course, with the figures above.
* **pnm-five** as a third course: the five-button style of the same charts.
* **Keysounds**: pop'n is keysounded (each button plays a sound); the mode
  plays the song.
* **A pop'n noteskin pack**: discs are drawn; a skin could replace them.
* **Staggered receptors** by row, and the pad lighting for dance's stage
  translated to pop'n's.
* **Ojama**, characters and the battle mode: pop'n's own, not Trypt Mania's.

### Sources

* RemyWiki, "pop'n music AC General Info" and "pop'n music Glossary", for
  the tiers, levels, options, scoring ratios and grades.
* StepMania `GameManager.cpp`, for the button names and the `pnm-five` and
  `pnm-nine` steps types and column layouts; the StepMania wiki's supported
  game modes.
* The Be-Music Script format pages and Angolmois's PMS preset, for the PMS
  channel mapping.
* Japanese player wikis (ポップン百科大事典 and the 上級曲難易度表 wiki) and
  measurement blogs, for the gauge's internal numbers and the judgement
  frames; Namu Wiki's judgement table for COOL's one frame and the pop'n
  music 8 Q&A.
