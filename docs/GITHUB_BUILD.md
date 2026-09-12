# Build Archive in GitHub Actions

GitHub builds this project on a clean Ubuntu machine with JDK 17 and Gradle 8.9. Android Studio is optional.

## One-time setup

1. Create a new **private** repository on GitHub. Do not add a README, `.gitignore`, or license there.
2. Extract the Archive ZIP on your computer.
3. Open a terminal inside the extracted folder and run:

```bash
git init
git add .
git commit -m "Initial Archive Android project"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPOSITORY.git
git push -u origin main
```

Replace `YOUR_USERNAME` and `YOUR_REPOSITORY` with the values from GitHub.

## Download the APK

1. Open the repository on GitHub.
2. Select **Actions**.
3. Open **Android Cloud Build**.
4. Open the newest successful run.
5. Download the `Archive-debug-apk` artifact from the Artifacts section.
6. Extract the downloaded artifact and install its APK on the Android phone.

The APK is a debug build intended for personal testing. Android may ask you to allow installation from your browser or file manager.

## Build again

Every push to `main` starts a build automatically. You can also select **Run workflow** on the workflow page without changing code.

## If a build fails

Open the failed run and expand **Run tests and build APK**. Copy that step's complete error output; it contains the useful failure rather than an Android Studio networking error.
