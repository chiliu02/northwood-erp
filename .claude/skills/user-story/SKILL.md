---
name: user-story
description: Write, review, split or rewrite a user story in docs/user-stories/ and its acceptance criteria (its detailed design is the detailed-design skill). Use whenever a story is drafted or changed, a backlog of stories is checked, or someone asks whether a piece of work is a user story or what a good one looks like.
---

# Writing a user story

**A user story names one party, one thing that party can do or get, and why it matters to them. It
describes the system from outside and says nothing about how it works inside. It is precise only in its
acceptance criteria.**

The research behind this is in
[`references/research.md`](references/research.md): where the term comes
from, the definitions from the Agile Alliance and Scrum Alliance, the three Cs, INVEST, common mistakes
and sources. Read it when a rule below needs its reason.

## What a story is, and isn't

- **The outside view.** A story describes behaviour that a party can see. Modules, classes, tables and
  the like are the inside view. They belong to the story's detailed design, not the story.
- **A placeholder for a conversation, not a requirements spec** (Ron Jeffries' three Cs). The sentence
  is the *Card*: it records that a need exists and why. The detail comes out of the *Conversation*, and
  the *Confirmation*, meaning the acceptance criteria and their tests, makes it precise. A story can
  start thin and gain detail as it gets close to being built.
- **Not required by Scrum.** The Scrum Guide says *Product Backlog items*. A story is one way to write
  one.

## Before writing

- **Read the glossary** in `docs/domain.md`, and the sections of `docs/domain.md`,
  `docs/architecture.md` and `docs/infrastructure.md` the story will cite. Write the story in the
  glossary's words, with its meanings. A word the story needs but the glossary lacks is a gap to
  raise. Don't quietly coin one.
- **Follow the conventions in `CLAUDE.md`**, which records the story format below.
- **Check the existing backlog** in `docs/backlog.md` and `docs/user-stories/`. The story, or a neighbour it overlaps, may
  already exist.

## The rules

1. **The party is someone who acts and benefits**: a named role from the project's vocabulary, never a
   generic *user* when the project has more than one kind. Different parties make different stories.
   *Developer*, *system* and *the code* are usually signs of inside work, not parties.
2. **Outside view only.** The sentence names no module, class, table, column or code path. It may name
   a surface the party uses: "through the API", "in the app", "by email". When the party is another
   program or an agent, the API is its outside view.
3. **The benefit belongs to the party.** "So that the code is cleaner" or "so that the tables exist" is
   nobody's benefit. That's inside work (see below).
4. **The acceptance criteria can be observed and tested.** Each one says what the party sees, or what a
   request returns, once the story is done. Include refusals and edge cases: who is refused, and how it
   looks to them. Use *Given / When / Then* where it reads better. A criterion that can only be checked
   by reading the code belongs in the detailed design.
5. **INVEST** (Bill Wake): Independent, Negotiable, Valuable, Estimable, Small, Testable. The checks most
   often failed are *Small* (does it fit in one iteration?) and *Independent*.
6. **Split vertically.** Split a story that is too big by party, by outcome, by rule, or by happy path
   versus edge cases. Never split by layer ("the database part", "the UI part").
7. **Trace it**, if the project keeps higher-level documents such as a domain model, architecture or
   infrastructure: cite the sections the story builds on, and only documents above it.

## Inside work

Some real work has no party who sees it: planning, design, setting up a schema, deployment,
integration testing, refactoring. By the definition above it isn't a story, and it is never written as
one ("As the system, I want…"). It is a work item of type **task** (the work item types are in
`docs/domain.md`). When it serves a story, it is a child of that story; otherwise it stands on
its own. A task has no user story file.

How a story's design names the tasks it needs is the `detailed-design` skill.

## File layout

As decided in `CLAUDE.md`: a story is one file, `docs/user-stories/US-nnn-<slug>.md`,
written when the need is known and kept free of anything inside the system. Its detailed design is a
separate file in `docs/detailed-designs/`, paired by id and slug, and is the `detailed-design` skill's job. **A story
file has no "In short" blocks** unless the owner asks for one: the story sentence is the short version.

```markdown
# US-nnn: <the outcome, in a few words>

## Story
As a **<party>**, I want <what they can do or get>, so that <why it matters to them>.

## Acceptance
- <observable outcome, including refusals and edge cases>

## Notes
<anything that makes the story clearer, still from outside: the scenarios or steps the party goes
through, the smaller stories it was split into (never by layer), examples, and what the conversation
settled, with its date>

## Open questions
- <what the conversation still has to settle>

## Traces
<sections of `docs/domain.md`, `docs/architecture.md` and `docs/infrastructure.md` it builds on>
```

The story never links to its design: the design is written from the story, not the other way round.

## Reviewing a story: the checklist

- [ ] The party is a named role from the project's vocabulary, and the right one.
- [ ] Neither the sentence nor the notes name anything inside the system: that is the design's job.
- [ ] The benefit belongs to the party.
- [ ] Every acceptance criterion can be observed from outside and tested, refusals included.
- [ ] Small enough for one iteration, and not split by layer.
- [ ] Every word is the project's word, in its sense.
- [ ] Traces, if kept, cite only documents above the story, and those sections say what's claimed.
- [ ] Nothing in it quietly decides a question that belongs to the owner or a higher-level document.

## Common mistakes

- Copying a requirements document's structure into stories.
- Treating a story as a finished spec rather than the start of a conversation.
- Expecting every story to have the same level of detail, however far off it is.
- Confusing stories with use cases.
- Making a story out of a technical component or a UI screen.
