"""A partial or unsigned Android build must never become the public APK link."""
import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('site_builder',Path(__file__).with_name('build-site.py'))
builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)

def release(version='0.1.0',names=None):
    tag='android-v'+version
    names=names or [f'Sift-{version}-android.apk',f'Sift-{version}-android-build-info.json','SHA256SUMS-android.txt']
    return {'tag_name':tag,'published_at':'2026-10-17T00:00:00Z','draft':False,
            'assets':[{'name':n,'state':'uploaded','size':100,'browser_download_url':builder.REPO+'/releases/download/'+tag+'/'+n} for n in names]}
class AndroidReleaseTests(unittest.TestCase):
    def test_complete_signed_filename_is_exposed(self):
        data=builder.android_data([release()])
        self.assertEqual(data['ANDROID_HIDDEN'],'')
        self.assertTrue(data['ANDROID_URL'].endswith('/Sift-0.1.0-android.apk'))
    def test_unsigned_or_incomplete_artifact_remains_hidden(self):
        for names in [['Sift-0.1.0-android-unsigned.apk','Sift-0.1.0-android-build-info.json','SHA256SUMS-android.txt'],['Sift-0.1.0-android.apk']]:
            self.assertEqual(builder.android_data([release(names=names)])['ANDROID_HIDDEN'],'hidden')
    def test_incomplete_new_release_retains_previous_complete_apk(self):
        older=release();newer=release('0.2.0',['Sift-0.2.0-android.apk']);newer['published_at']='2026-10-18T00:00:00Z'
        self.assertEqual(builder.android_data([older,newer])['ANDROID_VERSION'],'v0.1.0')
    def test_foreign_asset_url_is_rejected(self):
        data=release();data['assets'][0]['browser_download_url']='https://example.com/app.apk'
        with self.assertRaises(ValueError):builder.android_data([data])
if __name__=='__main__':unittest.main()
