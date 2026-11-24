# Builds app.html from src/app.template.html (font embedded). Usage: python3 build.py path/to/kalpurush.woff2
import base64,sys,os
here=os.path.dirname(os.path.abspath(__file__))
t=open(os.path.join(here,"src/app.template.html"),encoding="utf-8").read()
b=base64.b64encode(open(sys.argv[1],"rb").read()).decode()
out=t.replace("__FONT__",b).replace("__SEED__",'{"v":3,"items":[],"days":{}}')
for p in [os.path.join(here,"app.html")]+sys.argv[2:]: open(p,"w",encoding="utf-8").write(out)
