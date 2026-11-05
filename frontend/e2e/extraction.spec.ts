import {test,expect} from '@playwright/test';
test('saves and restores delta-only rules while deriving full-table defaults',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));const requests:any[]=[];let saved:any=null;let slowTables=false;
 const remember=(input:any)=>{const options={...(saved?.tableOptions??{})};for(const id of input.tableIds)options[id]={delta:input.tableOptions[id]?.delta??null};saved={...input,tableOptions:options};};
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let body:unknown={};
  if(path==='/api/status')body={status:'UP',database:'H2',migrationVersion:'19'};
  else if(path==='/api/connections')body=[{id:1,name:'Oracle extraction',databaseType:'ORACLE',schema:'APP'},{id:2,name:'H2 test',databaseType:'H2',schema:'PUBLIC'}];
  else if(path==='/api/csrf')body={};
  else if(path.endsWith('/tables')){if(slowTables)await new Promise(resolve=>setTimeout(resolve,150));body=path.includes('/1/')?[{id:10,schema:'APP',name:'CUSTOMERS',loadStrategy:'Full',updateFrequency:'Daily'},{id:11,schema:'APP',name:'ACCOUNTS',loadStrategy:'Delta',updateFrequency:'Weekly'}]:[];}
  else if(path.endsWith('/extraction-project/configuration')){if(route.request().method()==='POST')remember(route.request().postDataJSON());else if(!slowTables)await new Promise(resolve=>setTimeout(resolve,150));body={settings:saved};}
  else if(path.includes('/extraction-project/columns/'))body=[{name:'NAME',dataType:'VARCHAR2',numeric:false,deltaType:'TEXT'},{name:'ID',dataType:'NUMBER',numeric:true,deltaType:'NUMBER'},{name:'LAST_UPDATED',dataType:'DATE',numeric:false,deltaType:'DATE'}];
  else if(path.endsWith('/extraction-project')){const input=route.request().postDataJSON();requests.push(input);remember(input);await route.fulfill({contentType:'application/zip',body:'ZIP'});return;}
  else throw new Error('Unexpected request '+path);
  await route.fulfill({json:body});
 });
 await page.goto('/generation');await expect(page.getByRole('heading',{name:'Extraction project',exact:true})).toBeVisible();
 const table=page.getByRole('combobox',{name:'Table options',exact:true});
 await expect(table.locator('option')).toHaveText(['Choose a table','APP.ACCOUNTS']);
 await expect(page.getByRole('combobox',{name:'Control sum column',exact:true})).toHaveCount(0);
 await expect(page.getByRole('textbox',{name:'Scheduler group',exact:true})).toHaveCount(0);
 await page.getByRole('checkbox',{name:'Extract APP.ACCOUNTS',exact:true}).uncheck();
 await expect(page.getByRole('button',{name:'Generate source project ZIP'})).toBeEnabled();
 let download=page.waitForEvent('download');await page.getByRole('button',{name:'Generate source project ZIP'}).click();expect((await download).suggestedFilename()).toBe('EDAC_Extraction_JDBC_Project.zip');
 expect(requests[0]).toMatchObject({tableIds:[10],engine:'JDBC',delimiter:'\u0007',tableOptions:{}});
 await page.getByRole('checkbox',{name:'Extract APP.ACCOUNTS',exact:true}).check();
 await table.selectOption({label:'APP.ACCOUNTS'});
 await expect(page.getByText('Control sum column: ID (automatic).',{exact:true})).toBeVisible();
 await expect(page.getByRole('combobox',{name:'Extraction rule',exact:true}).locator('option')).toHaveText(['Delta range','Delta flag']);
 await page.getByRole('combobox',{name:'Delta column',exact:true}).selectOption('LAST_UPDATED');
 await expect(page.getByText(/For business date 20261105/)).toBeVisible();
 await expect(page.getByRole('combobox',{name:'Lower bound source',exact:true})).toHaveValue('BUSINESS_DATE');
 await page.getByRole('combobox',{name:'Extraction method',exact:true}).selectOption('SQLCL');
 await page.getByRole('textbox',{name:'Installed SQLcl executable path',exact:true}).fill('/tools/sqlcl/bin/sql');
 await page.getByRole('combobox',{name:'Delimiter',exact:true}).selectOption('CUSTOM');await page.getByRole('textbox',{name:'Custom delimiter',exact:true}).fill('||');
 await page.getByRole('spinbutton',{name:'Parallel extraction jobs',exact:true}).fill('3');
 await page.getByRole('checkbox',{name:'Extract APP.CUSTOMERS',exact:true}).uncheck();
 await page.getByRole('button',{name:'Save configuration',exact:true}).click();await expect(page.getByRole('status').filter({hasText:'Configuration saved.'})).toBeVisible();
 expect(saved.tableOptions['11'].delta).toMatchObject({mode:'RANGE',column:'LAST_UPDATED',lowerSource:'BUSINESS_DATE',upperSource:'BUSINESS_DATE_END'});
 // Exercise restoration when configuration arrives before the table list.
 slowTables=true;await page.reload();await expect(page.getByRole('button',{name:'Generate source project ZIP'})).toBeEnabled();
 await expect(page.getByRole('combobox',{name:'Extraction method',exact:true})).toHaveValue('SQLCL');await expect(page.getByRole('textbox',{name:'Custom delimiter',exact:true})).toHaveValue('||');
 await expect(page.getByRole('spinbutton',{name:'Parallel extraction jobs',exact:true})).toHaveValue('3');
 await expect(page.getByRole('checkbox',{name:'Extract APP.CUSTOMERS',exact:true})).not.toBeChecked();
 await table.selectOption({label:'APP.ACCOUNTS'});await expect(page.getByRole('combobox',{name:'Delta column',exact:true})).toHaveValue('LAST_UPDATED');
 download=page.waitForEvent('download');await page.getByRole('button',{name:'Generate source project ZIP'}).click();expect((await download).suggestedFilename()).toBe('EDAC_Extraction_SQLCL_Project.zip');
 expect(requests[1]).toMatchObject({engine:'SQLCL',delimiter:'||',parallelJobs:3,tableIds:[11],tableOptions:{'11':{delta:{column:'LAST_UPDATED',mode:'RANGE'}}}});
 // Save a full-only selection; previously saved delta rules must survive.
 await page.getByRole('checkbox',{name:'Extract APP.ACCOUNTS',exact:true}).uncheck();await page.getByRole('checkbox',{name:'Extract APP.CUSTOMERS',exact:true}).check();
 await page.getByRole('button',{name:'Save configuration',exact:true}).click();await expect(page.getByRole('status').filter({hasText:'Configuration saved.'})).toBeVisible();
 slowTables=false;await page.reload();await expect(page.getByRole('button',{name:'Generate source project ZIP'})).toBeEnabled();
 await expect(page.getByRole('checkbox',{name:'Extract APP.ACCOUNTS',exact:true})).not.toBeChecked();await page.getByRole('checkbox',{name:'Extract APP.ACCOUNTS',exact:true}).check();
 await table.selectOption({label:'APP.ACCOUNTS'});await expect(page.getByRole('combobox',{name:'Delta column',exact:true})).toHaveValue('LAST_UPDATED');
 await page.getByRole('combobox',{name:'Delta column',exact:true}).selectOption('ID');await expect(page.getByRole('combobox',{name:'Lower bound source',exact:true})).toHaveValue('PARAMETER');
 await expect(page.getByRole('combobox',{name:'Lower bound source',exact:true}).locator('option')).toHaveText(['Last successful checkpoint','Run parameter']);
 await page.getByRole('combobox',{name:'Source database connection',exact:true}).selectOption({index:1});await expect(page.getByRole('button',{name:'Generate source project ZIP'})).toBeDisabled();expect(errors).toEqual([]);
});
