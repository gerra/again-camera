package sh.gerra.again

/** Pages of the app's own site, again.gerra.sh: site/ in this repository (docs/site.md). */
internal object Links {
    /**
     * Google Play and the App Store want the privacy policy linked from inside the app as well
     * as from the store listing. tools/test_deploy_site.py checks it is the page the site serves.
     */
    const val PRIVACY_POLICY = "https://again.gerra.sh/privacy"
}
