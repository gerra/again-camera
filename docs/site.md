# The website: again.gerra.sh

Again's own site, set up the way [gains](https://github.com/gerra/gains) ships `gains.gerra.sh`:
static pages on the same Hetzner box, behind nginx. Google Play and the App Store need a privacy
policy URL, and the App Store a support URL; the site gives them both, and the app links to the
privacy policy from its home screen, as Google Play asks.

| Page | URL | File |
|------|-----|------|
| Landing page | `https://again.gerra.sh/` | [`site/index.html`](../site/index.html) |
| Privacy policy | `https://again.gerra.sh/privacy` | [`site/privacy.html`](../site/privacy.html) |
| Support | `https://again.gerra.sh/support` | [`site/support.html`](../site/support.html) |

Plain HTML and one stylesheet in the app's colours, no build step and no JavaScript. nginx serves
`/privacy` from `privacy.html` ([`deploy/nginx/again.gerra.sh.conf`](../deploy/nginx/again.gerra.sh.conf)).
The contact address on every page is `again@gerra.sh`.

## How it deploys

The [Deploy site workflow](../.github/workflows/deploy-site.yml) runs on a push to `main` that
touches `site/`, `deploy/nginx/` or the deploy script, and from **Actions › Deploy site › Run
workflow**. Its steps are [`tools/deploy_site.py`](../tools/deploy_site.py):

1. The tests in [`tools/test_deploy_site.py`](../tools/test_deploy_site.py): every local link and
   image on the pages resolves, every page gives the contact address, and the app's privacy link
   (`composeApp/.../Links.kt`) is the page the site serves.
2. `check-secrets`: until the three secrets below exist, the run summary says which are missing
   and the job stops there, green.
3. `nginx`: copies the vhost to the box, `nginx -t`, reload. A vhost whose certificate isn't on
   the box yet is skipped, with the `certbot` command to run.
4. `site`: rsyncs `site/` into `/var/www/again.gerra.sh`, then checks that
   `https://again.gerra.sh/privacy` answers 200.

From a laptop with the box's host alias (`hetzner_gb`, or another in `REMOTE`), the same two
steps run without the workflow:

```bash
python3 tools/deploy_site.py nginx
python3 tools/deploy_site.py site
```

## One-time setup

In this order, since the certificate needs the DNS record and the vhost needs the certificate:

1. **DNS**: an A record (and AAAA, if the box has IPv6) for `again.gerra.sh`, pointing at the same
   box as `gains.gerra.sh`.
2. **Certificate**, once the name resolves:
   ```bash
   ssh hetzner_gb 'certbot certonly --nginx -d again.gerra.sh'
   ```
   Always `certonly`, never bare `certbot --nginx`, whose installer adds server blocks of its own.
3. **The contact address**: make `again@gerra.sh` reach your inbox, the way `gains@gerra.sh`
   does. The privacy policy promises an answer within 30 days.
4. **Secrets**, under **Settings › Secrets and variables › Actions** in this repository, the
   same values as gains' deploy workflows:

   | Secret | What it is |
   |--------|------------|
   | `DEPLOY_HOST` | The box's address. |
   | `DEPLOY_USER` | The user to connect as. |
   | `DEPLOY_KEY` | That user's private SSH key, the whole file. |
5. **Deploy**: merge to `main`, or run the workflow by hand. Check that
   `https://again.gerra.sh/privacy` loads.
6. **The stores**: the privacy policy URL in the Play Console (Policy › App content › Privacy
   policy) and in App Store Connect, and the support URL in App Store Connect.

## Keeping it true

The privacy policy says what the app does: the same as the README's Privacy section,
`iosApp/iosApp/PrivacyInfo.xcprivacy`, the App Store's "Data Not Collected" and Google Play's Data
safety answers. A new permission, a network connection or anything else that changes those
changes `site/privacy.html` too, with a new date at the top, before the version of the app that
does it is released.

The screenshots on the landing page are `docs/screenshots` as WebP, 600 px wide:

```bash
convert docs/screenshots/03-compare.png -resize 600x -quality 85 site/img/shot-compare.webp
```

with `05-home-returning` as `shot-home`, `02-camera` as `shot-camera`, `04-saved` as
`shot-saved` and `06-camera-permission` as `shot-permission`.
