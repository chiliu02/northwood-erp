---
name: high-level-design
description: Write, review or amend the project's high-level design documents, the architecture and the infrastructure that sit above user stories (docs/architecture.md and docs/infrastructure.md). Use when one is created, a section is added or changed, a story or its detailed design needs something they lack, or they are checked for consistency. Stories are the user-story skill; a story's detailed design is the detailed-design skill.
---

# Writing the high-level design

**A project's high-level design is two documents that everything below them builds on. The
architecture says how the system sits in the world and how its code is layered. The infrastructure
document says what exactly gets built: data, interfaces, and the choices that are not obvious. Both describe the system as
it is designed to be, and both take their words from the domain.**

## Where they sit

Each level builds only on the levels above it, and cites only them:

| Level | Answers | Takes from |
| --- | --- | --- |
| Domain (glossary, domain model) | what the problem is, in its own language, with no technology | nothing |
| **Architecture** | the boundary, who depends on whom, the surfaces, cross-cutting concerns, the layers and their rules | the domain |
| **Infrastructure** | the data model and storage, the interfaces to it, notable queries and formats, a sketch of the code, and why the less obvious parts are there | the domain and the architecture |
| User stories | one need of one party, and how to tell it is done (`user-story` skill) | the three above |
| A story's detailed design | the parts that one story adds or changes (`detailed-design` skill) | all above |
| Code | the stories, built | all above |

**Change flows down, never up.** When something below needs a term, rule or table these documents
lack, the highest document it belongs in is amended first, then the ones below it. **A fact goes in the
lowest document that can state it.** If writing it needs a word from a higher document, it's in the
wrong place.

The vocabulary is the glossary in `docs/domain.md`. A term these documents need and the glossary
lacks is added there first (`domain-design` skill).

## Before writing

- **Which document**: `docs/architecture.md` or `docs/infrastructure.md`. Decide from the fact to be
  written, using the table above and the lists below. Ask if it could go in either.
- **Follow the project's conventions** in `CLAUDE.md` (how sections are cited, house style) over the
  defaults here.
- **Read the documents above** the one you are writing, whole, and the one you are writing.
- **Measure, don't remember.** A claim about the code is checked with a command; a claim about a
  library or another system cites its source.

## What goes in the architecture

- **The boundary**: what the system is, what it depends on, what depends on it, and what it
  deliberately does not know.
- **How it is reached**: its surfaces (API, UI, tools, events) and what they share.
- **Cross-cutting concerns** decided once for every surface: identity and authorisation, errors,
  configuration, observability.
- **The layers** (or modules), what each may depend on, the reason for each rule, and how the rules
  are enforced.
- **Left out, and open**: what the architecture deliberately doesn't cover, and its open questions.

## What goes in the infrastructure

- **The data model**: an entity-relationship diagram, then each table or store, column by column,
  with constraints.
- **Storage and formats** that aren't obvious from the model: how metadata is stored, how text is
  rendered, notable queries.
- **The interfaces** between layers, such as the data-access interfaces and their implementations,
  named in the domain's words.
- **A sketch of the code**: the main types in the domain's words, placed in the architecture's layers.
- **Why the less obvious parts are there**, so a later reader doesn't undo them.
- **Left out**: what the infrastructure deliberately doesn't cover yet.

## The rules

1. **State what it builds on**, at the top: the documents above it and how they are cited (such as
   *domain §5*). It cites nothing below it: no story, no code path as a source.
2. **Every section opens with a short answer**: what the question is and what the answer is, in at most
   three sentences, before the detail. It is a summary, never a second claim.
3. **Designed behaviour only.** No "not built yet", "currently", or defect lists: what exists today is
   the code's job, and a gap is work to do. The one exception is a short line saying a guard that
   enforces a rule exists, so the reader knows the rule is checked.
4. **The domain's words, in the domain's sense.** A new term is added to the glossary first. Two senses
   never share a word, and a qualifier is not a fix.
5. **Give the reason for every rule and every non-obvious choice.** The reasoning lives here, not in
   code comments. A code comment cites the section instead.
6. **Section numbers are stable.** A section that moves leaves a pointer rather than renumbering the rest,
   because stories, designs and code cite them.
7. **Decisions only.** An open question is listed as open, in the document's open-questions section. The
   discussion that settles it is kept elsewhere (a history or decisions folder), and the decision moves
   here when it's made.
8. **A rule that can be checked by a test gets one**, with a case that plants a breach and shows the
   check fails.

## Default layouts

Use these only when the project has no format of its own.

```markdown
# <System>: the architecture

> **In short** — <what the system is, what it depends on, how it is reached>

**It builds on [`domain.md`](domain.md)**, cited as *domain §n*.

## 1. The boundary
## 2. Surfaces
## 3. <cross-cutting concern, one section each>
## n. The layers, and the rules that are enforced
## n+1. Left out, and open
```

```markdown
# <System>: the infrastructure

> **In short** — <what exactly gets built>

**It builds on [`domain.md`](domain.md) and [`architecture.md`](architecture.md)**, cited as
*domain §n* and *architecture §n*.

## 1. The entity-relationship diagram
## 2. The tables, column by column
## 3. <storage, formats and notable queries, one section each>
## n. The data-access interfaces
## n+1. A sketch of the domain code
## n+2. Why the less obvious parts are there
## n+3. Left out
```

## Reviewing a document: the checklist

- [ ] It says what it builds on, and cites only documents above it.
- [ ] Every section opens with a short answer of at most three sentences that agrees with the detail.
- [ ] No status, no "not built yet", no defect lists.
- [ ] Every term is the glossary's, in its sense. Nothing new was coined here.
- [ ] Every fact is in the lowest document that can state it.
- [ ] Every rule and non-obvious choice has its reason.
- [ ] No section was renumbered, and moved sections leave a pointer.
- [ ] Open questions are listed as open, not quietly settled.
- [ ] Documents below that cite a changed section still hold, or are amended in the same change.
