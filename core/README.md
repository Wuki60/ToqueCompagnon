# core

This folder is a copy of `core/` from [Toque](https://github.com/Wuki60/MrCuisineVibeConnect) at
commit `a1ac964d707cf3ff0dc123aeeb9c33defe03ff3b` (Toque 0.12.0), with two fields that are not in an official release yet
(Toque pull request #135): `JobWire.ferment` and `PhoneWire.defaultName`. It holds the pure Kotlin code shared with the robot app: the link protocol,
the safety rules, the recipes, the translations helper. `src/demo` is the demo robot that runs on a PC.

- It is edited in the Toque repository, never here.
- To refresh it, copy `core/src/main` and `core/src/demo` from Toque again.
- Its tests run in the Toque repository.
