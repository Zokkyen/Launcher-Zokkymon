## User Input

Inspect and execute this single task in workspace c:\Users\steph\Downloads\ZokkymonLauncher.

Task: adjust Discord/game detection behavior so that when launching the game from this launcher, Discord should identify/display the game as "Zokkymon <modpack version>" instead of "Cobblemon Academy 2".

Required outcomes:
1. Determine what currently controls the name Discord displays when the game is running.
2. Determine whether the desired behavior is fully achievable through the existing Discord Rich Presence integration or whether it depends on Discord process/game detection outside Rich Presence.
3. If a code or config change is feasible and appropriate, implement the minimal focused change.
4. Validate the change with the narrowest practical validation available.
5. Return a concise report containing:
- what controls the currently displayed name in Discord,
- whether the desired behavior is fully achievable,
- exact files changed, if any,
- validation performed,
- any unavoidable limitation or required manual Discord-side setup.

Relevant context:
- Existing Rich Presence code is in src/main/java/com/zokkymon/launcher/DiscordPresenceService.java with related launcher wiring.
- Current observed behavior: Discord shows "Cobblemon Academy 2" when the game launches.
- Desired behavior: Discord should show "Zokkymon 1.1.0" or generally "Zokkymon <modpack version>".

Please inspect the codebase, make changes only if they are actually feasible, and explain clearly if Discord-side limitations prevent a full solution.

**Project started**: 2026-06-01T22:19:23Z

## Tasks

### Phase: Analysis
- 🔄 t1 [architect] Tracer ce qui contrôle le nom Discord affiché et cadrer le changement minimal faisable (dispatched 2026-06-01T22:19:23Z)

### Phase: Implementation
- ⏳ t2 [backend] Ajuster minimalement le libellé Rich Presence pour privilégier Zokkymon et sa version [deps: t1]

### Phase: Validation
- ⏳ t3 [architect] Vérifier par smoke-check ciblé la source du libellé Discord et la cohérence du changement [deps: t2]
- ⏳ t4 [tester] Valider le comportement atteignable et consigner les limites Discord inévitables [deps: t3]