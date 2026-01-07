# SnipSnip

A ShareX-like screenshot tool for KDE Plasma

## Vibe Coding™ Results

This was a "vibe coding" experiment with Claude Code (Opus), to see if Claude Code is actually good as people say that it will kill all developers or not, because I wanted to see the "state of the art" of vibe coding instead of worrying if it will actually kill all developers or not.

I've already had experiences with Claude Opus 4.5 and other LLM models in IntelliJ IDEA via the AI Assistant plugin. I've already used agentic tools (Junie) in IntelliJ IDEA.

* Claude Code DID NOT "one-shot" the application. The first bug was that the program wasn't able to extract the monitor geometry when using the `kscreen-doctor` CLI tool. The bug was that the `kscreen-doctor -o` gives the output with ANSI color codes, so the Claude Code RegEx wasn't matching correctly.
    * I nudged it in the right place by saying "heyyy... why not use THE `--json` ARGUMENT IF YOU ARE GOING TO PARSE IT???". However it makes you think: Couldn't Claude Code figure out that the output has ANSI color codes and think "hmmm, shouldn't I disable coloring somehow, or strip it from the output before parsing it?"
* After that it worked... but the application did not work correctly according to the specification: The application always tried screenshotting the entire screen (because plasmashell is technically a full screen application), manual crop with drag did not work, manual crop did work but the crop preview was wonky because it wasn't respecting my monitor scaling settings (I use 1.5x).
* After asking it to fix all of these issues, manual crops and the preview was working, but selecting a specific window STILL did not work. At this point, I've decided to debug the code and see what was going on...
    * The issue was that the RegEx that Claude made to match the window's geometry and position was also wrong. The reason why I think it made the RegEx incorrectly is because both of the `kdotool getwindowgeometry` invocations that Claude made did not contain any fractional numbers. I've decided to fix this bug manually by replacing all the Ints with Doubles. (I could've also just truncated the number to a integer... which could've been easier)
* And then *finally* the app was working somewhat well...? Now the issue is that the app was not ignoring apps that were behind another app (even though this was explicitly said in the first prompt, but oh well), so I asked it to fix. However, in the middle of the process, I hit my limit... fun!
* It took ~two hours from the time I paid for Claude Code until I spent all of my Claude Code Pro plan tokens, and now I need to wait until 5pm to use it again...

Now, of course, it is impressive that a machine can do all of that... but I think that there is a LOT of grifters trying to shill AI and trying to fearmonger that programming as a job is joever. In reality, unless you are rich and are willingly to spend tons of money, you can't just "vibe code" your way to a working application... unless if it is a trivial application.

I'm wondering if a lot of people just code trivial applications and that's why they are surprised that ✨ AI ✨ can do their work.

The Claude Code conversation can be found in [claudecode.txt](claudecode.txt).