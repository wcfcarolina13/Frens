import os, sys

f1 = "src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java"
with open(f1, "r") as f: s = f.read()

s = s.replace(
    "    public static class BotSpawn {\n        private String dimension;\n        private double x;",
    "    public static class BotSpawn {\n        private String levelName;\n        private String dimension;\n        private double x;"
)
s = s.replace(
    "public BotSpawn(String dimension, double x, double y, double z, float yaw, float pitch) {\n            this.dimension = dimension;",
    "public BotSpawn(String levelName, String dimension, double x, double y, double z, float yaw, float pitch) {\n            this.levelName = levelName;\n            this.dimension = dimension;"
)
s = s.replace(
    "        public String dimension() {\n            return dimension;\n        }",
    "        public String levelName() {\n            return levelName;\n        }\n\n        public String dimension() {\n            return dimension;\n    import os, sys

f1 = "src/mais 
f1 = "src/ma


with open(f1, "r") as f: s = f.read()

s = s.replace(
    "    publiSe
s = s.replace(
    "    public stat s     "    publ=     "    public static class BotSpawn {\n        private String levelName;\n        private S    ManualC)
s = s.replace(
    "public BotSpawn(String dimension, double x, double y, double z, float yaw, float pitch) {\n            this.dimensonId    "public Beg    "public BotSpawn(String levelName, String dimension, double x, double y, double z, float yaw, float pitch) {\n            this.levelie)
s = s.replace(
    "        public String dimension() {\n            return dimension;\n        }",
    "        public String levelName() {\n            return levelName;\n  ControlApplier.java"
    "         "    "        public String levelName() {\n            return levelName;\n        }\  
f1 = "src/mais 
f1 = "src/ma


with open(f1, "r") as f: s = f.read()

s = s.replace(
    "    publiSe
s = s.replace(
    "    public stat s     "    publ=     "    publice()f1 = "src/ma

wi

with openns(n
s = s.replace(
    ns.OPERATOR_PERMISS    "    publ  s = s.replace(
on    "    publsps = s.replace(
    "public BotSpawn(String dimension, double x, double y, double z, float yaw, float pitch) {\n            this.dimensonommandDi    "public Bers = s.replace(
    "        public String dimension() {\n            return dimension;\n        }",
    "        public String levelName() {\n            return levelName;\n  ControlApplier.java"
    "         "    "        public String levelName() {\n            returnCONFIG.    "        li    "        public String levelName() {\n            return levelName;\n  Contro() !    "         "    "        public String levelName() {\n            return levelName;\n      .gf1 = "src/mais 
f1 = "src/m)
with open(f3, "w") as f: f.write(s)

print("Patch complete!")
