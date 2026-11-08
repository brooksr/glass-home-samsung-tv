# Brookstream Watch list

The movies and shows on our streaming services, for the Watch tab in the
Android app (`android/.../Watch.kt`). Home build only: the shareable build
doesn't get the tab.

- **Services** (`SERVICES` in `build.py`): Netflix, Prime Video, Disney+, Hulu,
  Paramount+, Tubi and YouTube's free movies. Ad tiers count; rentals don't.
- **What's on each one:** TMDB's watch-provider data (from JustWatch), US. For
  each service it takes the 600 most popular movies and 300 most popular shows,
  plus the 200 best-rated movies and 100 best-rated shows (`PAGES`). Titles with
  fewer than 25 TMDB votes are left out. That's about 5,000 titles, 3.7 MB.
- **Details** (TMDB): genres, year, runtime or seasons, age rating, plot, cast,
  director or creator, poster, YouTube trailer, IMDb ID.
- **IMDb rating and votes:** IMDb's free dataset, `title.ratings.tsv.gz`.

```
python3 -I tools/watch/build.py tools/watch/out     # TMDB_KEY from .env or the environment
```

It takes about 4 minutes. Responses are cached in `out/cache` for a day, so a
rerun is quick. `out/` is git-ignored. The Android build bundles
`out/watch.json` as a fallback, if it exists.

## On the Home Assistant laptop

The app reads `$HA_URL/local/roche-home/watch/watch.json` and checks it at most
every 6 hours, or when you pull down to refresh. Catalogues change weekly, so
rebuild weekly. On the laptop, `~/roche-watch` holds `build.py` and
`tmdb_key` (mode 600). This crontab line runs it Mondays at 5 am and swaps in
the new list only when the build succeeds:

```
0 5 * * 1 cd $HOME/roche-watch && TMDB_KEY=$(cat tmdb_key) python3 build.py out > build.log 2>&1 && cp out/watch.json /home/broche/homeassistant/www/roche-home/watch/watch.json.part && mv /home/broche/homeassistant/www/roche-home/watch/watch.json.part /home/broche/homeassistant/www/roche-home/watch/watch.json
```

After editing `build.py`, copy it over: `scp tools/watch/build.py "${HA_SSH}:roche-watch/"`.
Use the braces: in zsh, `$HA_SSH:r…` is read as a modifier.

## Opening a title

"Watch on Netflix" (and the other services) opens the service's search for the
title, in its app if the app takes the link, otherwise in the browser. TMDB
doesn't give the services' own title IDs. For a direct link, use "Direct links
to watch (TMDB)" on the title's page: it goes to TMDB's where-to-watch page,
whose logos link straight to the title in each service.
