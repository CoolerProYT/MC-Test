# MCTest docs

VitePress site for MCTest at https://mctest.coolerpromc.com/, published to GitHub Pages by `.github/workflows/docs.yml` on pushes to the default branch.

```bash
cd docs
npm install
npm run dev       # serves http://localhost:5173
npm run build     # builds to .vitepress/dist
```

The workflow builds with `DOCS_BASE=/` because the site is served from the root of the custom domain `mctest.coolerpromc.com`
(set under the repository's Settings > Pages). Without it, the base is `/MC-Test/` for `https://<user>.github.io/MC-Test/`, which is
also what `npm run dev` uses locally.

The tool tables in `reference/tools.md` and `reference/server-tools.md` mirror the tables in the repository README.
