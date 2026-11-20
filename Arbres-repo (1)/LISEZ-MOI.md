# Arbres 1.6

## Compiler
GitHub Actions (`.github/workflows/build.yml`) ou `mvn package` -> `target/Arbres-1.6.jar` (Java 25, Paper 26.3).
Supprime l'ancien `config.yml` du plugin avant de relancer.

## Changements 1.6
- Pics de glace refaits : un groupe au plus par case de 18x18 blocs (espacés, plus de mur de glace), pics plus fins, en glace et glace bleue.
- Les pics du jeu (glace compactée) sont supprimés sans jamais toucher à nos pics, même au bord d'un chunk.
- Le niveau du sol est retrouvé même dans un champ de pics très dense.

Les chunks déjà générés gardent leurs anciens pics : à régénérer pour voir le résultat.
