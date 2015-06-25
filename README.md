# flatcheck
A small app to help you find your new apartment, written is Scala.  

## Short description
This app is designed to rerun a cached query on a regular basis, and monitor if the query has new results since the last run. The motivation behind the program was simple: I was searching for apartments for sale on multiple sites, but none of them had a proper notification system implemented. What I sought was pretty simple though: the user specifies what he/she wants in a detailed search. The site sends a notification to the user if a new deal is available that mathces the user's criteria.
This program does precisely that: you set up your query, and the program reruns at a specified rate, and sends you the new results. The found results are saved to disk so that the program will only send a results once, even if it's restarted. To learn about the usage, please refer to this.

## Technical details
The program is managed with sbt, which makes the source code really lightweight: it's only one source file and some sbt config files that describe the libraries used in the app. Also included in the project is the great sbt plugin, [sbt-assembly](https://github.com/sbt/sbt-assembly). With its help, you can generate a self-contained .jar file of the project with the command

`sbt assembly`

The resulting .jar file will be at ./target/scala-version/

## Disclaimer

This app comes with absolutely now warranty. Also, please do not use it for unethical purposes like spamming or generating unnecessary traffic.
