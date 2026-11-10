#!/usr/bin/env python3
"""Live test of OpenAI-native PDF file uploads; PDFs must remain single files."""
import base64, json, sys, textwrap, urllib.request, urllib.error
BASE="http://127.0.0.1:8090"
UA="NemApi-PDF-regression/1.1"
MARKER="PDF_VISION_MARKER_4B62"
PAGE9="PAGE9_DIRECT_PDF_OK"

def build_pdf(page_texts):
    nl=b"\n"; n=len(page_texts)
    objs=[b"<< /Type /Catalog /Pages 2 0 R >>"]
    kids=" ".join(f"{3+2*i} 0 R" for i in range(n))
    objs.append(f"<< /Type /Pages /Kids [{kids}] /Count {n} >>".encode())
    font_id=3+2*n
    for i,text in enumerate(page_texts):
        safe=text.replace("\\","\\\\").replace("(","\\(").replace(")","\\)")
        lines=textwrap.wrap(safe,width=42) or [""]
        cmds=["BT /F1 18 Tf 72 700 Td"]
        for j,line in enumerate(lines):
            if j: cmds.append("0 -28 Td")
            cmds.append("("+line+") Tj")
        cmds.append("ET"); content=" ".join(cmds).encode()
        page_id=3+2*i; stream_id=page_id+1
        objs.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 {font_id} 0 R >> >> /Contents {stream_id} 0 R >>".encode())
        objs.append(b"<< /Length "+str(len(content)).encode()+b" >>"+nl+b"stream"+nl+content+nl+b"endstream")
    objs.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    out=bytearray(b"%PDF-1.4"+nl); offsets=[0]
    for i,obj in enumerate(objs,1):
        offsets.append(len(out));out+=str(i).encode()+b" 0 obj"+nl+obj+nl+b"endobj"+nl
    xref=len(out);out+=b"xref"+nl+b"0 "+str(len(objs)+1).encode()+nl+b"0000000000 65535 f "+nl
    for off in offsets[1:]:out+=f"{off:010d} 00000 n ".encode()+nl
    out+=b"trailer"+nl+b"<< /Size "+str(len(objs)+1).encode()+b" /Root 1 0 R >>"+nl+b"startxref"+nl+str(xref).encode()+nl+b"%%EOF"+nl
    return bytes(out)

def post(path,body):
    req=urllib.request.Request(BASE+path,data=json.dumps(body).encode(),method="POST",headers={"Content-Type":"application/json","User-Agent":UA})
    try:
        with urllib.request.urlopen(req,timeout=300) as r:return r.status,json.loads(r.read().decode())
    except urllib.error.HTTPError as e:return e.code,json.loads(e.read().decode())

def message(status,data):
    return data["choices"][0]["message"].get("content","")

def one_pdf_part(uri,name="nemapi-test.pdf"):
    return {"type":"file","file":{"filename":name,"file_data":uri}}

def verify_one_upload(label):
    req=urllib.request.Request(BASE+"/api/logs",headers={"User-Agent":UA})
    with urllib.request.urlopen(req,timeout=10) as r: entries=json.load(r).get("entries",[])
    recent=" ".join(e.get("message","") for e in entries[-24:])
    if "1 fichier(s) transmis au composeur" not in recent:
        raise RuntimeError(label+": le journal ne confirme pas un seul fichier transmis")

def main():
    raw=build_pdf(["Harmless PDF test. Exact marker: "+MARKER])
    uri="data:application/pdf;base64,"+base64.b64encode(raw).decode()
    print("one-page PDF:",len(raw),"bytes")
    body={"model":"deepseek-chat","stream":False,"messages":[{"role":"user","content":[
        {"type":"text","text":"Read the attached PDF and return only its exact marker."},one_pdf_part(uri)]}]}
    status,data=post("/v1/chat/completions",body);text=message(status,data)
    print("Chat Completions:",status,repr(text[:100]))
    if status!=200 or MARKER not in text:raise RuntimeError("Chat Completions did not read the direct PDF file")
    verify_one_upload("Chat Completions")

    body={"model":"deepseek-chat","stream":False,"input":[{"role":"user","content":[
        {"type":"input_text","text":"Read the attached PDF and return only its exact marker."},
        {"type":"input_file","filename":"nemapi-test.pdf","file_data":uri}]}]}
    status,data=post("/v1/responses",body);text="".join(p.get("text","") for o in data.get("output",[]) for p in o.get("content",[]) if p.get("type")=="output_text")
    print("Responses API:",status,repr(text[:100]))
    if status!=200 or MARKER not in text:raise RuntimeError("Responses API did not read the direct PDF file")
    verify_one_upload("Responses API")

    multi=build_pdf([f"Ordinary text on page {i}." for i in range(1,9)]+["The exact marker is "+PAGE9])
    multi_uri="data:application/pdf;base64,"+base64.b64encode(multi).decode()
    body={"model":"deepseek-chat","stream":False,"messages":[{"role":"user","content":[
        {"type":"text","text":"Read page 9 of the single attached PDF and return only its exact marker."},one_pdf_part(multi_uri,"nine-pages.pdf")]}]}
    status,data=post("/v1/chat/completions",body);text=message(status,data)
    print("9-page single file:",status,len(multi),"bytes",repr(text[:100]))
    if status!=200 or PAGE9 not in text:raise RuntimeError("Nine-page PDF was not read as one native file")
    verify_one_upload("9-page PDF")

    invalid="data:application/pdf;base64,"+base64.b64encode(b"not a PDF").decode()
    status,data=post("/v1/chat/completions",{"model":"deepseek-chat","messages":[{"role":"user","content":[one_pdf_part(invalid,"bad.pdf")]}]})
    err=data.get("error",{}).get("message","")
    print("invalid PDF:",status,err)
    if status!=400 or "PDF est invalide" not in err:raise RuntimeError("Invalid PDF error was not explicit")
    status,data=post("/v1/chat/completions",{"model":"deepseek-chat","messages":[{"role":"user","content":[{"type":"file","file":{"filename":"x.pdf","file_id":"file_test"}}]}]})
    err=data.get("error",{}).get("message","")
    print("file_id without Files API:",status,err)
    if status!=400 or "file_id nécessite" not in err:raise RuntimeError("Unsupported file_id error was not explicit")
    print("PASS: original PDF bytes remain one application/pdf file across both native shapes; page 9 read; errors explicit.")

if __name__=="__main__":
    try:main()
    except Exception as e:print("FAIL:",e,file=sys.stderr);sys.exit(1)
