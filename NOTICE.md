# Data and font notices

The code in this repository is under the MIT license. The bundled data and fonts have their own terms, which these notices are required to carry.

| What | Where | Source and license |
| --- | --- | --- |
| Rail lines, station positions, track shapes | `shared/src/commonMain/composeResources/files/network.json`, `geo.json` | (c) OpenStreetMap contributors, Open Database License 1.0. The derived database in these two files is offered under the same license. |
| Province and municipality borders | `geo.json` | Statistics Korea, SGIS, Korea Open Government License Type 1 |
| Congestion statistics and transfer distances | `congestion.json`, `network.json` | Seoul Metro, via the national open data portal |
| Ferrite Sans | `shared/src/commonMain/composeResources/font/ferrite_sans_*.otf` | Cut down and renamed from Pretendard, (c) 2021 Kil Hyung-jin. SIL Open Font License 1.1, see `licenses/OFL-sans.txt` |
| Ferrite Mono | `shared/src/commonMain/composeResources/font/ferrite_mono_*.ttf` | Cut down and renamed from JetBrains Mono, (c) 2020 The JetBrains Mono Project Authors. SIL Open Font License 1.1, see `licenses/OFL-mono.txt` |

Street maps at run time come from OpenFreeMap (c) OpenMapTiles, and walking routes and place search from public services, all on OpenStreetMap data. Their terms ask for attribution, which the app shows on its map screens and its licenses screen.
