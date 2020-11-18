# kotlinx-lincheck release checklist
Follow these steps to release a new `<version>` of `kotlinx-lincheck`.

1. Checkout `develop` branch: <br>
   `git checkout develop`

2. Retrieve the most recent `develop`: <br>
   `git pull`

3. Make sure the `master` branch is fully merged into `develop`: <br>
   `git merge origin/master`

4. Replace `<old-version>` with `<version>` in [`gradle.properties`](gradle.properties).

5. Commit the updated [`gradle.properties`](gradle.properties): <br>
   `git add gradle.properties` <br>
   `git commit -m "Release lincheck-<version>"`
   
6. Create a tag related to the releasing `<version>`: <br>
   `git tag lincheck-<version>`

7. Push the release commit and tag: <br>
   `git push --follow-tags`

8. Merge the new version into `master`: <br>
   `git checkout master` <br>
   `git merge develop` <br>
   `git push`
   
9. Switch into `develop` branch back: <br>
    `git checkout develop`

10. Clean and build the project (make sure that all tests are passed)
    and publish the artifacts: <br>
    `./gradlew build` <br>
    `./gradlew publish`

11. In [Bintray](https://bintray.com/kotlin/kotlinx/kotlinx.lincheck#) admin interface:
    * Publish artifacts of the new version.
    * Wait until newly published version becomes the most recent.
    * Sync to Maven Central.

12. In [GitHub](https://github.com/Kotlin/kotlinx-lincheck/releases) interface:
    * Create a release named `lincheck-<version>`.
    * Write a release notes message following the old ones as example of style.
    
13. Update the version to the next `SNAPSHOT` one in [`gradle.properties`](gradle.properties).

14. Commit and push the updated [`gradle.properties`](gradle.properties): <br>
   `git add gradle.properties` <br>
   `git commit -m "Prepare for next development iteration` <br>
   `git push`