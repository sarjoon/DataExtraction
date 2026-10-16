import { test, expect } from '@playwright/test';
test('mapping selections persist, reset by connection and hide multi-table columns',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 const excluded=new Set<string>();let failSave=false;let loads=0;
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let body:any={};
  if(path==='/api/connections')body=[{id:1,name:'Oracle A',databaseType:'ORACLE',schema:'APP'},{id:2,name:'Oracle B',databaseType:'ORACLE',schema:'APP'}];
  else if(path==='/api/status')body={status:'UP',database:'H2',migrationVersion:'3',consoleEnabled:true};
  else if(path.endsWith('/tables'))body=[{id:path.includes('/2/')?21:11,schema:'APP',name:'CUSTOMER'},{id:path.includes('/2/')?22:12,schema:'APP',name:'ACCOUNT'}];
  else if(path.endsWith('/columns')){loads++;body=[{position:1,name:'ID',dataType:'NUMBER',byteLength:22,precision:10,scale:0,nullable:false,included:!excluded.has(path+'ID')},{position:2,name:'NAME',dataType:'VARCHAR2',byteLength:100,characterLength:100,nullable:true,included:!excluded.has(path+'NAME')}];}
  else if(path.endsWith('/selection')){
   if(failSave){await route.fulfill({status:500,json:{message:'Selection could not be saved.'}});return;}
   const input=route.request().postDataJSON();const key=path.replace('/selection','')+input.columnName;
   input.included?excluded.delete(key):excluded.add(key);body=input;
  }
  await route.fulfill({json:body});
 });
 await page.goto('/mapping');
 await expect(page.getByLabel('Select APP.CUSTOMER',{exact:true})).toBeVisible();
 await page.getByLabel('Select APP.CUSTOMER',{exact:true}).check();
 await expect(page.getByLabel('Include NAME',{exact:true})).toBeChecked();
 await page.getByLabel('Include NAME',{exact:true}).uncheck();
 await expect(page.getByText('Column selection saved.',{exact:true})).toBeVisible();
 await page.reload();await page.getByLabel('Select APP.CUSTOMER',{exact:true}).check();
 await expect(page.getByLabel('Include NAME',{exact:true})).not.toBeChecked();
 await page.getByLabel('Include NAME',{exact:true}).check();await expect(page.getByText('Column selection saved.',{exact:true})).toBeVisible();expect(excluded.size).toBe(0);
 failSave=true;await page.getByLabel('Include NAME',{exact:true}).uncheck();
 await expect(page.getByRole('alert')).toContainText('Selection could not be saved.');await expect(page.getByLabel('Include NAME',{exact:true})).toBeChecked();failSave=false;
 const before=loads;await page.getByLabel('Select all tables',{exact:true}).check();
 await expect(page.getByText('Column details cannot be shown when multiple tables are selected.',{exact:true})).toBeVisible();
 await expect(page.getByLabel('Include NAME',{exact:true})).toHaveCount(0);expect(loads).toBe(before);
 await page.getByLabel('Select all tables',{exact:true}).uncheck();
 await expect(page.getByText('Select one table to view its columns.',{exact:true})).toBeVisible();
 await page.getByLabel('Select APP.CUSTOMER',{exact:true}).check();await expect(page.getByLabel('Include ID',{exact:true})).toBeChecked();
 await page.screenshot({path:'test-results/mapping.png',fullPage:true});
 await page.getByLabel('Source database connection',{exact:true}).selectOption({label:'Oracle B — APP'});
 await expect(page.getByLabel('Select APP.CUSTOMER',{exact:true})).not.toBeChecked();await expect(page.getByLabel('Include ID',{exact:true})).toHaveCount(0);
 await page.setViewportSize({width:390,height:844});await page.getByLabel('Select APP.CUSTOMER',{exact:true}).check();await expect(page.getByLabel('Include ID',{exact:true})).toBeChecked();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);expect(errors).toEqual([]);
});
test('late column responses cannot replace a multiple-table selection',async({page})=>{
 let release!:()=>void;const gate=new Promise<void>(resolve=>release=resolve);
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;
  let body:any={};
  if(path==='/api/connections')body=[{id:1,name:'Oracle',databaseType:'ORACLE',schema:'APP'}];
  else if(path==='/api/status')body={status:'UP',consoleEnabled:true};
  else if(path.endsWith('/tables'))body=[{id:11,schema:'APP',name:'CUSTOMER'},{id:12,schema:'APP',name:'ACCOUNT'}];
  else if(path.endsWith('/columns')){await gate;body=[{position:1,name:'STALE',included:true}];}
  await route.fulfill({json:body});
 });
 await page.goto('/mapping');await page.getByLabel('Select APP.CUSTOMER',{exact:true}).check();
 await expect(page.getByText('Loading columns from ORACLE…',{exact:true})).toBeVisible();
 await page.getByLabel('Select APP.ACCOUNT',{exact:true}).check();release();
 await expect(page.getByText('Column details cannot be shown when multiple tables are selected.',{exact:true})).toBeVisible();
 await expect(page.getByLabel('Include STALE',{exact:true})).toHaveCount(0);
});
