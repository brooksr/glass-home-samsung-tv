# Roche Home Library

Public-domain movies, TV, cartoons, LibriVox audiobooks and Project Gutenberg
books for the Library tile (`app/js/library.js`). Everything streams; the TV
stores only resume points.

- `sources.json`: the curated Internet Archive identifiers per row (`movies`,
  `tv`, `kids`, `audiobooks`), display titles (`titles`), and the books
  (Gutenberg number, title, author, matching LibriVox audiobook).
- `build.py sources.json OUT`: reads each item's file list from the Archive
  and keeps what the TV plays (h.264/MPEG4 MP4s, never HEVC; for a movie only
  its longest copy; DVD menus under 2 min dropped; LibriVox chapters once), and
  turns each Gutenberg text into `books/pg<n>.json` (chapters of paragraphs,
  headers/footers and `_italics_` marks removed) plus its cover. Fetches go
  through curl (this Mac's Python rejects some valid certificates).
- Upload: `scp OUT/catalog.json "$PI_SSH:$PI_WWW/roche-home/library/"` (both in `.env`)
  and `scp OUT/books/* …/library/books/`. HA serves them at
  `$HA_URL/local/roche-home/library/` (`library.base` in
  `app/js/config.js`). No app reinstall needed for catalogue changes.

Picking titles: the Archive's "most downloaded" lists aren't family-safe and
its user-uploaded TV collections include material that isn't public domain,
so titles are added by hand. Search, e.g.
`https://archive.org/advancedsearch.php?q=title:("Detour") AND collection:(feature_films) AND format:(h.264)&fl[]=identifier&fl[]=title&rows=5&output=json`,
check the item page, then add its identifier.

The TV can reach archive.org but not gutenberg.org (its 2018 certificate store
lacks the newer root), which is why books are served from the Pi.

Content filter: `exclude` in `sources.json` is a regular expression matched
against every TV and Kids file title. It leaves out the racist caricature
cartoons of the era (e.g. Tokio Jokio, Inki, Jungle Jitters, the Bosko shorts,
Little Black Sambo, Jasper), plus the redrawn/colorized duplicates. The build
log lists each file it left out ("left out ..."); check it after adding a
collection, since a title filter only catches what it names.
