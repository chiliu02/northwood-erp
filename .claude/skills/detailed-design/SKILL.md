---
name: detailed-design
description: Write, review or update the detailed design of a user story, a task or a defect in docs/detailed-designs/ (US-, TK-, DF-nnn-<slug>.design.md), the inside view (the story itself is the user-story skill). Use before any code is written for a work item, and whenever a design is drafted or checked.
---

# Writing a story's detailed design

**A detailed design says how the system will do what one user story asks: which parts it adds or
changes, where they go, what they are called, and how each acceptance criterion is shown to hold. It
is the inside view of the story, and it lives in its own file in `docs/detailed-designs/`, paired with
the story by id and slug.**

**A task or a defect has a design file too, and no story file:** `TK-nnn-<slug>.design.md`,
`DF-nnn-<slug>.design.md`. It cites its row in the backlog and the story it is a child of, and has no
*Tasks* section. **The design is written, and reviewed by the owner, before any code** (`CLAUDE.md`).

## Why a separate file

- **Different views.** The story is the outside view: a party, a need, acceptance criteria. The design
  is the inside view: modules, types, storage, endpoints. Keeping them apart keeps the story readable by
  whoever accepts it, and keeps the design's readers from mistaking it for the requirement.
- **Different times.** A story can sit in the backlog, thin and negotiable, long before anyone designs
  it. The design is written just before the story is built, and changes along with the code.
- **One direction.** The design is written from its story and cites it. The story never links to its
  design.
- **Focused context.** An agent refining or accepting a story loads only the story. An agent building it
  loads both.

## Before writing

- **Find the story** in `docs/user-stories/`, from the conversation. Ask if it is unclear which one.
- **Read the story whole**: its acceptance criteria, notes, open questions and traces. An open question
  the design needs answered goes back to the owner. Don't settle it in the design.
- **Look for an existing design file** in `docs/detailed-designs/` (`<id>-<slug>.design.md`). Update it
  rather than starting over.
- **Read the documents the design must follow**: `docs/architecture.md` (its layers, and which may
  depend on which), `docs/infrastructure.md` (storage, interfaces), `docs/domain.md` (the glossary and
  the model), and the conventions in `CLAUDE.md`.
- **Read the code the story touches.** A design names real modules and types, not guesses.

## The rules

1. **Cite the story first**, by id and link, then give the approach in a line.
2. **Take every name and placement from the documents above it.** Types and modules are named in the
   glossary's words and placed in the layer the architecture gives them. Storage and interfaces follow
   the infrastructure document. A design never coins a term or a layer.
3. **Change flows down.** If the design needs something the documents above it lack (a term, a rule, a
   table), amend the highest document it belongs in first, then the story if it changes, then the
   design. Don't quietly add it here. Amending the architecture or design is the `high-level-design`
   skill.
4. **Build only what the story asks.** A behaviour the design discovers that the story doesn't ask for
   goes back to the story (its notes or open questions), not into the design.
5. **Map every acceptance criterion to a test.** A criterion with no test isn't designed yet. Say at
   what level each is tested.
6. **Name the tasks the story needs.** Work no party sees, such as a schema, a shared check or a
   deployment step, is a work item of type task, a child of the story. The design lists each task
   and what it changes. A task another story already did is cited, not repeated.
7. **Keep it as long as the change needs.** A design is for the one story. What would outlast it, such
   as a rule for every story or a decision about the whole system, belongs in the project's
   architecture or infrastructure documents.
8. **Put each rule in the domain layer.** Each rule of the domain that the story builds is a method of an
   aggregate, a value that refuses what is not valid, a domain service or a specification. The design
   names which one holds each rule. A use case only reads, asks the domain, and writes, so an `if` in a
   use case that checks a rule is a rule in the wrong layer (agent-common `docs/Daos.design.md`, *What
   stays in the domain*).

## File layout

As decided in `CLAUDE.md`: `docs/detailed-designs/US-nnn-<slug>.design.md`, paired with
`docs/user-stories/US-nnn-<slug>.md`, and **every section opens with an "In short" block**. A task's or a defect's file
opens with `Designs TK-nnn (…), a child of US-nnn.` and leaves out *Tasks*.

```markdown
# US-nnn: detailed design

Designs [US-nnn](../user-stories/US-nnn-<slug>.md). <one line: the approach>

## Changes

> **In short** — <what the story adds or changes inside, in at most three sentences>

The modules, types, storage, endpoints and other inside parts it adds or changes, each placed and
named as `docs/architecture.md` and `docs/infrastructure.md` say.

## Tasks

> **In short** — <the tasks this story needs, or "none">

<the tasks, each a child of the story, with what it changes>

## Tests

> **In short** — <how the story is shown to be done>

<each acceptance criterion, and the test that shows it holds>

## Open questions

> **In short** — <what building it still has to settle, or "none">

- <each question>
```

## Reviewing a design: the checklist

- [ ] It cites its story, and builds nothing the story doesn't ask for.
- [ ] Every section opens with an "In short" block that agrees with its detail.
- [ ] Every name is the glossary's word, and every placement is the one the architecture gives.
- [ ] Nothing in it needs a term, rule or table missing from the documents above it.
- [ ] Every acceptance criterion maps to a test.
- [ ] Each rule of the domain that the story builds names the method, value, domain service or
      specification that holds it, and no use case decides a rule.
- [ ] Every task the story needs is named and is a child of the story, and a task done for another
      story is cited, not repeated.
- [ ] Anything that would outlast the story has moved to the project's architecture or infrastructure
      documents.
