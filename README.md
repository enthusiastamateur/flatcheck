# flatcheck
A small app to help you find your new apartment, written is Scala.  

## Short description
This app is designed to rerun a cached query on a regular basis, and monitor if the query has new results since the last run. The motivation behind the program was simple: I was searching for apartments for sale on multiple sites, but none of them had a proper notification system implemented. What I sought was pretty simple though: the user specifies what he/she wants in a detailed search. The site sends a notification to the user if a new deal is available that mathces the user's criteria.
This program does precisely that: you set up your query, and the program reruns at a specified rate, and sends you the new results. The found results are saved to disk so that the program will only send a results once, even if it's restarted. To learn about the usage, please refer to this.

## Building the project
The program is built using sbt, using the `sbt-native-packager` plugin.

1. Install sbt (or use e.g. IntelliJ's bundled version)
2. Open an sbt shell
3. Execute `stage`

This will create Unix & Windows start scrips under `target\universal\stage\bin`. You can use this version to test locally.

To create a zip file with the startup script and the libraries included, call

`universal:packageBin`

## Disclaimer

This app comes with absolutely now warranty. Also, please do not use it for unethical purposes like spamming or generating unnecessary traffic.
