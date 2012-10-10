 This directory contains some shell scripts that are used by CI (Jenkins) to
 build packages of PuppetDB for testing (and eventually for promotion to
 release packages).

 I'm not proud of this.  The contents of the shell script(s) that
 here need to be reconciled with Moses' standardized packaging
 stuff, and a lot of the contents should probably be ported over to
 Ruby instead of just shipping the nasty scripts.  However, this first step
 at least gives us 1) VCS for this stuff, and 2) the ability to run
 the two packaging builds in parallel.