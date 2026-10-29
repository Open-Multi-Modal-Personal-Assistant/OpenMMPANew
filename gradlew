#!/usr/bin/env bash

##############################################################################
##
##  Gradle start up script for UN*X (WristMind / OpenMMPANew)
##
##############################################################################

DEFAULT_JVM_OPTS=""
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
MAX_FD="maximum"

warn ( ) { echo "$*"; }
die ( ) { echo; echo "$*"; echo; exit 1; }

PRG="$0"
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done
SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Auto-copy gradle-wrapper.jar from sibling OpenMMPA repo if not yet staged
if [ ! -f "$CLASSPATH" ] && [ -f "/home/csaba/repos/AIML/OpenMMPA/OpenMMPA/android/gradle/wrapper/gradle-wrapper.jar" ]; then
    mkdir -p "$APP_HOME/gradle/wrapper"
    cp "/home/csaba/repos/AIML/OpenMMPA/OpenMMPA/android/gradle/wrapper/gradle-wrapper.jar" "$CLASSPATH"
fi

# Prefer Android Studio JBR (JDK 21) when JAVA_HOME is unset so Gradle 9.4 runs cleanly
if [ -z "$JAVA_HOME" ] && [ -x "/home/csaba/android-studio/jbr/bin/java" ]; then
    export JAVA_HOME="/home/csaba/android-studio/jbr"
fi

if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
    [ -x "$JAVACMD" ] || die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command found."
fi

function splitJvmOpts() {
    JVM_OPTS=("$@")
}
eval splitJvmOpts $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS
JVM_OPTS[${#JVM_OPTS[*]}]="-Dorg.gradle.appname=$APP_BASE_NAME"

exec "$JAVACMD" "${JVM_OPTS[@]}" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
