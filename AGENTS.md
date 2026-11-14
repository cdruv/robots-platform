# Robotics Platform

This is a repository for individual personal robotics projects.
There is no shared platform implementation yet, will extract reusable components as we progress.

## Repository Guidance and Policies
- **Module design:** Prefer deep, cohesive modules with simple, explicit interfaces that minimize what callers must know and coordinate.
- **Code comments policy:** Do not restate nearby code or add boilerplate comments/docstrings. Add comments for non-obvious intent, contracts, constraints, rationale, or explanations that materially aid understanding. Keep them close to the relevant code and update them when it changes. Brief section headers are allowed when they improve navigation. 
- **AGENTS.md and other agent facing files:** Keep changes conservative and concise. Record only durable, actionable guidance and non-obvious constraints that are not readily discoverable from existing sources. Reference canonical definitions instead of copying them. Amend existing guidance rather than appending overlapping rules; remove stale guidance when encountered. Omit code inventories. If possible, create and keep agentic files hierarchical (local AGENTS.md and other agentic files in projects, modules) to allow progressive exploration by the agents.
- **Human facing docs:** Do not create or expand standalone human-facing documentation unless explicitly requested. Keep implementation facts in code, schemas, configuration, and tooling. Keep local rationale and contracts beside the code; record durable guidance for future agent work in the relevant AGENTS.md. Do not turn agent files into general purpose documentation or create files merely to report completed work.
- **Tests:** Add or update tests for meaningful behavior, realistic failure cases, and regressions not adequately covered already. Test specifications and observable contracts; derive expectations independently of the implementation. Avoid tests that merely confirm language/library behavior, incidental internal structure, or trivial forwarding, unless they protect a project-specific contract or integration. Prefer focused tests and existing infrastructure. Do not add tests solely to increase coverage or test counts, or weaken valid assertions to make code pass.
- Build, test, deployment, hardware, and safety requirements are defined
  by each project rather than at the repository root.

## Robot projects

- [Walky — two legged Pixel-8-based robot](Walky/AGENTS.md)
- [Rovbot — 2WD Romi-based rover; project stashed](Rovbot/AGENTS.md)