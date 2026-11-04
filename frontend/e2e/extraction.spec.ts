import {test,expect} from '@playwright/test';
test('generates one selected extraction method with table rules and configurable SQLcl path',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));const requests:any[]=[];
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let body:unknown={};
  if(path==='/api/status')body={status:'UP',database:'H2',migrationVersion:'18'};
  else if(path==='/api/connections')body=[{id:1,name:'Oracle extraction',databaseType:'ORACLE',schema:'APP'},{id:2,name:'H2 test',databaseType:'H2',schema:'PUBLIC'}];
  else if(path==='/api/csrf')body={};
  else if(path.endsWith('/tables'))body=path.includes('/1/')?[{id:10,schema:'APP',name:'CUSTOMERS',loadStrategy:'Full',updateFrequency:'Daily'},{id:11,schema:'APP',name:'ACCOUNTS',loadStrategy:'Full',updateFrequency:'Weekly'}]:[];
  else if(path.includes('/extraction-project/columns/'))body=[{name:'ID',dataType:'NUMBER',numeric:true,deltaType:'NUMBER'},{name:'NAME',dataType:'VARCHAR2',numeric:false,deltaType:'TEXT'}];
  else if(path.endsWith('/extraction-project')){requests.push(route.request().postDataJSON());await route.fulfill({contentType:'application/zip',body:'ZIP'});return;}
  else throw new Error('Unexpected request '+path);
  await route.fulfill({json:body});
 });
 await page.goto('/generation');await expect(page.getByRole('heading',{name:'Extraction project',exact:true})).toBeVisible();
 await expect(page.getByRole('checkbox',{name:'Extract APP.CUSTOMERS',exact:true})).toBeChecked();
 let download=page.waitForEvent('download');await page.getByRole('button',{name:'Generate source project ZIP'}).click();expect((await download).suggestedFilename()).toBe('EDAC_Extraction_JDBC_Project.zip');
 expect(requests[0]).toMatchObject({tableIds:[10,11],engine:'JDBC',delimiter:'\u0007',sqlclExecutable:null,parallelJobs:1,tableOptions:{'10':{group:'Daily',delta:{mode:'FULL'}},'11':{group:'Weekly'}}});
 await page.getByRole('combobox',{name:'Table options',exact:true}).selectOption({label:'APP.CUSTOMERS'});
 await page.getByRole('combobox',{name:'Control sum column',exact:true}).selectOption('ID');
 await page.getByRole('combobox',{name:'Extraction rule',exact:true}).selectOption('RANGE');
 await page.getByRole('combobox',{name:'Delta column',exact:true}).selectOption('ID');
 await expect(page.getByRole('combobox',{name:'Lower bound source',exact:true})).toHaveValue('PARAMETER');
 await page.getByRole('combobox',{name:'Lower bound source',exact:true}).selectOption('CHECKPOINT');
 await page.getByRole('textbox',{name:'Initial lower bound (optional)',exact:true}).fill('0');
 await page.getByRole('checkbox',{name:'Extract APP.ACCOUNTS',exact:true}).uncheck();
 await page.getByRole('combobox',{name:'Extraction method',exact:true}).selectOption('SQLCL');
 await page.getByRole('textbox',{name:'Installed SQLcl executable path',exact:true}).fill('/tools/sqlcl/bin/sql');
 await page.getByRole('combobox',{name:'Delimiter',exact:true}).selectOption('CUSTOM');await page.getByRole('textbox',{name:'Custom delimiter',exact:true}).fill('||');
 download=page.waitForEvent('download');await page.getByRole('button',{name:'Generate source project ZIP'}).click();expect((await download).suggestedFilename()).toBe('EDAC_Extraction_SQLCL_Project.zip');
 expect(requests[1]).toMatchObject({engine:'SQLCL',delimiter:'||',sqlclExecutable:'/tools/sqlcl/bin/sql',tableIds:[10],tableOptions:{'10':{controlSumColumn:'ID',delta:{mode:'RANGE',column:'ID',lowerSource:'CHECKPOINT',upperSource:'PARAMETER',initialValue:'0',lowerOperator:'>',upperOperator:'<='}}}});
 expect(Object.keys(requests[1].tableOptions)).toEqual(['10']);expect(requests[1]).not.toHaveProperty('format');
 await page.getByRole('combobox',{name:'Source database connection',exact:true}).selectOption({index:1});await expect(page.getByRole('button',{name:'Generate source project ZIP'})).toBeDisabled();expect(errors).toEqual([]);
});
