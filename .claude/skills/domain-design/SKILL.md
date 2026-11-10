---
name: domain-design
description: Write, review or amend the domain document (docs/domain.md), the top of the design chain that states the problem in its own language with no technology, with its ubiquitous language (glossary), subdomains, rules, aggregates, value objects, events, domain services, specifications and views. Also decides how to model a domain concept, and checks code or lower documents against the domain. Architecture and infrastructure are the high-level-design skill; stories are user-story; a story's design is detailed-design.
---

# Designing the domain

**The domain document says what the problem is, in the problem's own language, with no technology. Its
glossary is the vocabulary every document below it and all the code must use, and its model (the
aggregates, values, events, services and rules) is what the code is named after. It depends on
nothing, and everything depends on it.**

This follows Eric Evans' *Domain-Driven Design*, with one difference in emphasis: the model is written
down first, as a document, and the code is built from it. Code never invents vocabulary.

## Where it sits

| Level | Document | Skill |
| --- | --- | --- |
| 1 | **The domain** | this one |
| 2–3 | The architecture and the infrastructure | `high-level-design` |
| 4 | User stories | `user-story` |
| 5 | A story's detailed design | `detailed-design` |
| 6 | Code | none |

**Change flows down.** When a lower document or the code needs a term, rule or concept the domain
lacks, the domain is amended first, then the documents below it. **A fact goes in the lowest document
that can state it.** Tables, endpoints, frameworks and layers are not domain facts.

## Before writing

- **Read the domain document**, `docs/domain.md`, whole.
- **Follow the project's conventions** in `CLAUDE.md` for citing sections, short summaries, and
  naming, over the defaults here.
- **The language comes from the domain's experts, not from you.** Ask the owner what they call things,
  and check the reference domain (an established tool, a standard, a methodology) with sources. Offer
  two or three candidate words for anything new, say which you'd take and its weakness, and let the
  owner choose.

## What goes in the domain document

Default outline. Use the project's own if it has one. Keep section numbers stable once written.

1. **The reference point**: the established practice or product the domain is measured against, and
   where this one departs from it.
2. **Subdomains**: core (where the effort goes), supporting, generic. If there is more than one
   bounded context, the contexts and how they relate (a context map).
3. **The ubiquitous language**: a glossary of every term, with its meaning and, where useful, what it
   does *not* mean.
4. **Names still open**, each with its candidates.
5. **The rules**: who may do what, the invariants, and the policies, each in the glossary's words.
6. **Aggregates**: for each, its root, what is inside it, the invariants it guards, and which other
   aggregates it refers to (by identity only).
7. **Value objects**: each with what makes a value valid.
8. **Domain events**, in the past tense, and the history they make.
9. **Domain services and policies**: operations that belong to no single aggregate.
10. **Specifications**: rules that are combined, reused or used to select.
11. **Views**: the read-only projections the domain needs, such as a list or a board.
12. **Left out**: what the domain deliberately doesn't cover yet.
13. **Open questions.**

Open each section with a short answer of at most three sentences: what the question is, and what the
answer is.

## The rules for the language

1. **One word, one sense.** When a word would mean two things, take a different word. A qualifier
   ("domain task" versus "build task") asks every reader to keep making the distinction; a different word
   asks nobody.
2. **Record spent words.** A word taken by a neighbouring system, by the platform or by the
   language's standard library is listed as spent, with what it means there.
3. **No technology.** No table, column, endpoint, framework, file format or layer. If the sentence
   needs one, the fact belongs lower.
4. **Check a name against everything it will hold**, not against a summary: a name that's true of half
   of it is wrong.
5. **Singular unless one value holds many.** A service is named for what it does, a glossary word,
   not *XManager* or *XHelper*. Reach for *…Service* only when the bare word is taken.
6. **Metadata is not permission.** A label or attribute is for display, filtering and sorting. A rule
   that decides what someone may do is modelled as a rule, not read from a label.

## The rules for the model

1. **Value objects by default.** Model a concept as a value (immutable, equal by its attributes, valid
   once it exists) unless it has identity and a lifecycle.
2. **An entity is equal by identity.** In a language whose records compare by content, say so in the
   model: two states of one entity are the same entity.
3. **An aggregate is the boundary of one invariant**, changed in one transaction, reached only through
   its root. Keep it small. Refer to other aggregates by identity, never by holding them.
4. **Each aggregate root gets one storage interface**, declared by the domain and implemented outside
   it. Use the project's word for it: Evans says *repository*, others say *DAO*. Its name and methods
   are the infrastructure document's business, not the domain's.
5. **A domain event is a past-tense fact** named in the glossary's words. If history matters, the
   events are the history.
6. **A domain service only where no aggregate is the natural home**, stateless, and named as a
   glossary word.
7. **A specification for a rule that is combined, reused, or used both to check and to select.** A rule
   used once stays in its aggregate.
8. **In a functional style**, invariants are enforced by the constructor (a value that exists is
   valid), and a change returns a new value or the events it caused rather than mutating in place.
   The model's words don't change.
9. **Be practical.** A generic subdomain can be plain data. The effort goes to the core.

## Reviewing code or documents against the domain

Check that the domain's words reach the code unchanged:

- Every aggregate, value object, event, service and specification in the domain document is found in
  the code under that exact name, in the layer the architecture gives it.
- Nothing in the code is named with a word the glossary lacks, or with a glossary word in another
  sense.
- **Anti-patterns to flag:**
  - An anaemic model: logic in services, data in the aggregates.
  - A god aggregate.
  - A storage interface for something that isn't a root.
  - Infrastructure types in the domain layer.
  - Primitive obsession: a string where a value object belongs.
  - Invariants bypassed from outside the root.
  - A rule that reads a label.
  - *Manager*, *Handler* or *Processor* names.

Report each finding with the domain section it breaks (*domain §n*), what breaks, and whether the fix is
in the code or in the domain document. A gap in the domain is amended in the domain first.

## Reviewing the domain document: the checklist

- [ ] It depends on no other document, and names no technology.
- [ ] Every section opens with a short answer that agrees with the detail.
- [ ] Every term in the document is in the glossary, in one sense only.
- [ ] Words spent by neighbouring systems are listed.
- [ ] Every aggregate names its root, its contents, its invariants, and refers to others by identity.
- [ ] Every rule says who may do what in the glossary's words, and no rule reads metadata.
- [ ] Open names and open questions are listed as open, not quietly settled.
- [ ] Documents below that cite a changed section still hold, or are amended in the same change.

For migrating an existing codebase toward DDD step by step, the general `domain-driven-design` skill has
a phased plan.
