[out:json][timeout:280];
area["ISO3166-1"="KR"]->.kr;
relation["route"~"^(subway|light_rail|monorail|train)$"](area.kr)->.r;
.r out body;
node(r.r)->.n;
.n out body;
way(r.r)->.w;
.w out skel geom;
