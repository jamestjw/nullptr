const { mkdirSync, writeFileSync } = require('node:fs');
const path = require('node:path');
const { AndroidConfig, withAndroidManifest, withAppBuildGradle, withDangerousMod } = require('expo/config-plugins');

module.exports = function withAndroidBuild(config) {
  config = withAndroidManifest(config, config => {
    const application = AndroidConfig.Manifest.getMainApplicationOrThrow(config.modResults);
    application.$['android:usesCleartextTraffic'] = 'true';
    application.$['android:dataExtractionRules'] = '@xml/trackpad_data_extraction_rules';
    const features = config.modResults.manifest['uses-feature'] ??= [];
    for (const name of ['android.hardware.camera', 'android.hardware.camera.autofocus']) {
      const feature = features.find(feature => feature.$['android:name'] === name);
      if (feature) {
        feature.$['android:required'] = 'false';
      } else {
        features.push({ $: { 'android:name': name, 'android:required': 'false' } });
      }
    }
    return config;
  });

  config = withAppBuildGradle(config, config => {
    const { contents } = config.modResults;
    const react = /^react \{$/m;
    const debugSigning = /^    signingConfigs \{\n        debug \{\n[^}]+        \}\n    \}\n/m;
    const releaseSigning = /(^[ \t]*release \{\n)(?:[ \t]*\/\/[^\n]*\n)*[ \t]*signingConfig signingConfigs\.debug$/m;
    if (!react.test(contents) || !debugSigning.test(contents) || !releaseSigning.test(contents)) {
      throw new Error('The Expo Android template changed. Review the bundle and release signing configuration.');
    }
    config.modResults.contents = contents
      .replace(react, 'react {\n    debuggableVariants = []')
      .replace(debugSigning, '')
      .replace(releaseSigning, '$1            signingConfig null');
    return config;
  });

  return withDangerousMod(config, ['android', config => {
    const resources = path.join(config.modRequest.platformProjectRoot, 'app', 'src', 'main', 'res', 'xml');
    mkdirSync(resources, { recursive: true });
    writeFileSync(path.join(resources, 'trackpad_data_extraction_rules.xml'), `<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="." />
    </device-transfer>
</data-extraction-rules>
`);
    return config;
  }]);
};
