# Roche Home audiobooks

The Android app's Books tab: 94 critically acclaimed, popular or influential
public-domain books, read by LibriVox volunteers.

- `build.py`: the picks (`PICKS`: shelf, title, author, and LibriVox's own
  title or list of volumes when it differs). For each it asks the LibriVox
  API for every English recording, drops dramatic readings, abridgements and
  excerpts, and keeps the one downloaded most on the Internet Archive.
  Books LibriVox splits into volumes (War and Peace, Les Misérables, The
  Wealth of Nations, Democracy in America, Herodotus) are joined into one.
  Cover: the recording's full-size LibriVox art. Standard library only;
  about two minutes. Prints any pick it can't find.
- `audiobooks.json`: what it writes (books, their parts' MP3s and lengths),
  bundled into the app at build time. Rebuild the app after running it.

- `texts.py` (after `build.py`): the read-along text, `texts/<book id>.json`,
  one list of paragraphs per part. Only for recordings LibriVox says were
  read from a Project Gutenberg text, and only the same text. A beam search
  picks where each part starts: on a heading that names it (CHAPTER VI for
  "Chapter 6", "Inferno: Canto VI", "01 My Early Home"), with each part's
  length matching its reader's pace. A part whose start can't be confirmed
  against its heading gets no text, and a book where too few can gets none.
  About a minute and a half; the Gutenberg books are cached in `.cache/`.
  Both folders are left out of git (~45 MB); run it before building the app.
  69 books have text (2026-11-02). In the app the paragraphs' timing is an
  estimate (LibriVox's ~18 s spoken intro, then the part shared out by
  paragraph length), so the highlight can run a paragraph ahead or behind;
  tapping a paragraph jumps the audio there.

Not on LibriVox (checked 2026-11-01): Agatha Christie, Kafka's The Trial,
Plutarch's Lives.

Listening progress for books stays on the phone; it isn't sent to Home
Assistant with the podcast progress (the TV has no audiobooks).
