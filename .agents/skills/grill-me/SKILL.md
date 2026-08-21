---
name: grill-me
description: A relentless interview to sharpen a plan or design. Use when the user types /grill-me, or asks to be grilled, pressure-tested, or interviewed about an idea, plan, feature, or decision before committing to it.
disable-model-invocation: true
---

# grill-me

Interview the user relentlessly until you reach a shared understanding. Map this as a **design tree**: every decision branches into the decisions that hang off it.

Work the tree in **rounds**. The **frontier** is every decision whose prerequisites are already settled: the questions you can ask _now_ without guessing at answers you haven't heard yet. Ask the whole frontier in one round: number each question and give your recommended answer. Then wait for the user's answers before the next round.

Each question should be formatted like so:

```
❓ **Q1** - **<question title>**: <question body, might be multiple paragraphs, including multiple choices>

➡️ <your recommended answer>
```

Each round the user answers reshapes the tree: settled decisions push the frontier outward and unblock questions that depended on them. Recompute the frontier and ask the next round. A question whose answer depends on another question still open in this round belongs to a _later_ round, not this one.

Finding _facts_ is your job, never the user's. When a frontier question needs a fact from the environment (filesystem, tools, etc.), dispatch a sub-agent to find it; don't ask the user for anything you could look up yourself. Don't block on it: a running exploration is an unsettled prerequisite, so only the questions downstream of it wait for the sub-agent to report; ask the rest of the frontier now. The _decisions_ are the user's: put each to them and wait.

The session is done when the frontier is empty: every branch of the design tree visited, nothing left silently assumed. Do not act on it until the user confirms you have reached a shared understanding.

## Operating notes

- **Stateless.** Write no files and leave no workspace behind. The output of the session is a sharper idea in the user's head, not an artifact.
- **Don't rush to a plan.** Stay in inquiry. If the host agent has a "plan mode", this skill is not it — do not produce a plan document unless the user asks for one after the session.
- **Ungrillable questions.** Some questions ("one long form or three pages?", "how should this interaction feel?") cannot be settled by talking; they need something to react to. When you hit one, name it as ungrillable and suggest prototyping rather than burning rounds rephrasing it.
- **Push back.** A session with no disagreement is a session that wasn't needed. Challenge vague answers rather than accepting them. "I don't know" is a valid answer from the user and usually signals a prototype is needed.
- **Scope.** If the tree is ballooning past a few rounds, say so and propose breaking the work into smaller pieces to grill separately.

---

_Adapted from [mattpocock/skills](https://github.com/mattpocock/skills) (MIT), `grill-me` + `grilling`, merged into a single provider-agnostic file._
