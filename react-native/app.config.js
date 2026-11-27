module.exports = ({ config }) => ({
  ...config,
  version: process.env.ANDROID_VERSION_NAME ?? config.version,
  android: {
    ...config.android,
    versionCode: Number(process.env.ANDROID_VERSION_CODE ?? 1),
  },
});
