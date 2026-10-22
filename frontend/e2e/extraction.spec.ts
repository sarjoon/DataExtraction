import {test,expect} from '@playwright/test';

test('Extraction Scripts replaces placeholder and generates delimited and fixed packages',async({page})=>{
 const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message));const requests:any[]=[];
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let body:unknown={};
  if(path==='/api/status')body={status:'UP',database:'H2',migrationVersion:'12'};
  else if(path==='/api/connections')body=[{id:1,name:'Oracle extraction',databaseType:'ORACLE',schema:'APP'},{id:2,name:'H2 test',databaseType:'H2',schema:'PUBLIC'}];
  else if(path==='/api/csrf')body={};
  else if(path.endsWith('/tables'))body=path.includes('/1/')?[{id:10,schema:'APP',name:'CUSTOMERS'},{id:11,schema:'APP',name:'ACCOUNTS'}]:[];
  else if(path.includes('/extraction-scripts/columns/'))body=[{name:'ID',dataType:'NUMBER',byteLength:22},{name:'NAME',dataType:'VARCHAR2',byteLength:80}];
  else if(path.endsWith('/extraction-scripts')){requests.push(route.request().postDataJSON());await route.fulfill({contentType:'application/zip',body:'ZIP',headers:{'Content-Disposition':'attachment; filename=Oracle_Extraction_Scripts.zip'}});return;}
  else throw new Error('Unexpected request '+path);
  await route.fulfill({json:body});
 });
 await page.goto('/generation');await expect(page.getByRole('heading',{name:'Extraction Scripts',exact:true})).toBeVisible();
 await expect(page.getByRole('link',{name:'Extraction Scripts',exact:true})).toBeVisible();await expect(page.getByRole('link',{name:'Fixed-length files',exact:true})).toHaveCount(0);
 await expect(page.getByRole('checkbox',{name:'Extract APP.CUSTOMERS',exact:true})).toBeChecked();await expect(page.getByRole('checkbox',{name:'Extract APP.ACCOUNTS',exact:true})).toBeChecked();
 await page.getByRole('combobox',{name:'Delimiter',exact:true}).selectOption('SPACE');
 let download=page.waitForEvent('download');await page.getByRole('button',{name:'Generate script ZIP'}).click();expect((await download).suggestedFilename()).toBe('Oracle_Extraction_Scripts.zip');
 expect(requests[0]).toMatchObject({tableIds:[10,11],format:'DELIMITED',delimiter:' ',parallelJobs:15,envelope:true,systemCode:'PBI',countryCode:'SG'});
 await page.getByRole('combobox',{name:'Output format',exact:true}).selectOption('FIXED');await page.getByRole('combobox',{name:'Table options',exact:true}).selectOption({label:'APP.CUSTOMERS'});
 await expect(page.getByLabel('Width NAME',{exact:true})).toHaveValue('80');await page.getByLabel('Width NAME',{exact:true}).fill('100');await page.getByRole('combobox',{name:'Control sum column',exact:true}).selectOption('ID');
 await page.getByRole('checkbox',{name:'Extract APP.ACCOUNTS',exact:true}).uncheck();
 download=page.waitForEvent('download');await page.getByRole('button',{name:'Generate script ZIP'}).click();await download;
 expect(requests[1]).toMatchObject({tableIds:[10],format:'FIXED',widths:{'10:NAME':100},controlSumColumns:{'10':'ID'}});
 await page.getByRole('combobox',{name:'Source database connection',exact:true}).selectOption({label:'H2 test — H2'});await expect(page.getByRole('button',{name:'Generate script ZIP'})).toBeDisabled();
 expect(errors).toEqual([]);
});
