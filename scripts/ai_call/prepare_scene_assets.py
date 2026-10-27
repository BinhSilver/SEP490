"""Copy generated originals locally and encode web delivery assets without changing their content."""
import json
import shutil
import subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]

def main():
    manifest_path=ROOT/'data/ai-call/scene-images.json'
    manifest=json.loads(manifest_path.read_text(encoding='utf-8'))
    originals=ROOT/'target/ai-call-image-originals';originals.mkdir(parents=True,exist_ok=True)
    ready=0
    for item in manifest['images']:
        if not item.get('generatedSource'): continue
        source=Path(item['generatedSource'])
        original=originals/(item['key']+'.png')
        if not original.exists():shutil.copy2(source,original)
        output=ROOT/'src/main/resources/static'/item['url'].lstrip('/')
        output.parent.mkdir(parents=True,exist_ok=True)
        if not output.exists():
            subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-i',str(original),'-vf','scale=1280:-2',
                            '-c:v','libwebp','-quality','82',str(output)],check=True)
        item['bytes']=output.stat().st_size
        ready+=1
    manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Prepared {ready}/{len(manifest["images"])} scene illustrations for the web.')

if __name__=='__main__':main()
