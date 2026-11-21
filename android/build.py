import base64,json,glob,sys
font=sys.argv[1]; dump=sys.argv[2]
t=open("src/app.template.html",encoding="utf-8").read()
b=base64.b64encode(open(font,"rb").read()).decode()
items=[]
for f in sorted(glob.glob(dump+"/items/*.json")):
    import os
    if os.path.basename(f).startswith("sample-"): continue
    d=json.load(open(f,encoding="utf-8")); d=d.get("data",d); d["id"]=os.path.basename(f)[:-5]; d.pop("photo",None); items.append(d)
seed={"v":1,"items":items,"days":{},"lastBackup":0}
open("seed.json","w",encoding="utf-8").write(json.dumps(seed,ensure_ascii=False))
out=t.replace("__FONT__",b).replace("__SEED__",json.dumps(seed,ensure_ascii=False))
open("app.html","w",encoding="utf-8").write(out); print(len(out),len(items))
