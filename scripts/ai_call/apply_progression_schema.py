"""Apply the additive AI Call stage columns to the local jlas_aicall development database."""
import os,subprocess
from pathlib import Path
import yaml

ROOT=Path(__file__).resolve().parents[2]
config=yaml.safe_load((ROOT/'src/main/resources/application-local.yaml').read_text(encoding='utf-8'))
env=dict(os.environ)
for suffix,key in [('URL','url'),('USER','username'),('PASSWORD','password')]:env['AICALL_DB_'+suffix]=str(config['spring.datasource.'+key])
driver=Path.home()/'.m2/repository/com/mysql/mysql-connector-j/9.4.0/mysql-connector-j-9.4.0.jar'
subprocess.run(['java','-cp',str(driver),str(ROOT/'scripts/ai_call/ApplyProgressionSchema.java')],cwd=ROOT,env=env,check=True)
