# Static demo

`index.html`, `settings.html` and `demo-api.js` are the real Log Scraper pages with a small script that answers the API from recorded sample results, so they run from any static host (or the contest portal) with no server. Try `REQ-88231`, `REQ-88232`, `REQ-88233`.

Rebuild the pages after changing the real ones with `python3 demo/build_demo.py`. The language switch (Pengaturan → Bahasa) works here too: English answers are recorded alongside the Bahasa ones.
