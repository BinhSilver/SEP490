"""Update existing local AI Call content; preserve IDs, roles, visibility and sessions.

Requires PyYAML, Java 21 and the locally installed MySQL JDBC driver.
Only accepts the project's jlas_aicall development database.
Run with --apply after reviewing the generated seed; otherwise only validate.
"""
import argparse
import json
import os
import subprocess
from pathlib import Path
from generate_seed import validate

ROOT = Path(__file__).resolve().parents[2]

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    groups=validate(json.loads((ROOT/'data/ai-call/catalog.json').read_text(encoding='utf-8'))['scenarios'])
    roots_count=len(groups)
    total_cases=sum(len(g['cases']) for g in groups)
    legacy_count=total_cases-roots_count
    if not args.apply:
        print(f'Validated {roots_count} roots and {legacy_count} legacy cases. Use --apply to update the local database.')
        return
    import yaml
    config=yaml.safe_load((ROOT/'src/main/resources/application-local.yaml').read_text(encoding='utf-8'))
    env=dict(os.environ)
    for suffix,key in [('URL','url'),('USER','username'),('PASSWORD','password')]:
        env['AICALL_DB_'+suffix]=str(config['spring.datasource.'+key])
    folder=ROOT/'target/ai-call-catalog-update';folder.mkdir(exist_ok=True)
    slugs=[]
    for group in groups:
        for case in group['cases']:
            standard=case['code']=='standard'
            slug=group['slug'] if standard else group['slug']+'--'+case['code']
            learning=dict(case['learning'])
            if standard: learning.update(group['learning'])
            values=dict(title=group['title'] if standard else group['title']+' — '+case['title'],
                        variant_title=case['title'],description=case['description'],background=case['background'],
                        flow_json=case['flow'],learning_content_json=learning,thumbnail_url=group['thumbnail'])
            item=folder/slug;item.mkdir(exist_ok=True)
            for name,value in values.items():
                (item/(name+'.txt')).write_text(json.dumps(value,ensure_ascii=False) if isinstance(value,dict) else value,encoding='utf-8')
            slugs.append(slug)
    (folder/'slugs.txt').write_text('\n'.join(slugs),encoding='utf-8')
    driver=Path.home()/'.m2/repository/com/mysql/mysql-connector-j/9.4.0/mysql-connector-j-9.4.0.jar'
    env['AICALL_EXPECTED_ROOTS']=str(roots_count)
    env['AICALL_EXPECTED_SLUGS']=str(len(slugs))
    subprocess.run(['java','-cp',str(driver),str(ROOT/'scripts/ai_call/ApplyLocalCatalog.java')],cwd=ROOT,env=env,check=True)

if __name__=='__main__':main()
