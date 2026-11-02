import { test, expect } from '@playwright/test';

test('mapping generation requires a BAU choice and retains the JSON path without BAU',async({page})=>{
 let request:any;
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let body:any={};
  if(path==='/api/connections')body=[{id:1,name:'Source',schema:'APP',databaseType:'H2'}];
  else if(path==='/api/status')body={status:'UP',consoleEnabled:true};
  else if(path.endsWith('/tables'))body=[{id:11,schema:'APP',name:'NEW_TABLE'}];
  else if(path.endsWith('/columns'))body=[];
  else if(path.endsWith('/mapping-workbook')){request=route.request().postDataJSON();await route.fulfill({contentType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',body:'test workbook'});return;}
  await route.fulfill({json:body});
 });
 await page.goto('/mapping');await page.getByLabel('Select APP.NEW_TABLE',{exact:true}).check();
 const generate=page.getByRole('button',{name:'Generate mapping workbook',exact:true});await expect(generate).toBeDisabled();
 await page.getByLabel('Is a BAU mapping workbook available?',{exact:true}).selectOption('no');await expect(generate).toBeEnabled();
 const download=page.waitForEvent('download');await generate.click();await download;
 expect(request).toEqual({tableIds:[11]});
 await expect(page.getByRole('status')).toContainText('Mapping workbook downloaded.');
});

test('BAU upload validates files, preserves selection after failure and refreshes the registry after success',async({page})=>{
 let removed=false,fail=true,multipart='';let refreshed=0;
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let body:any={};
  if(path==='/api/connections')body=[{id:1,name:'Source',schema:'APP',databaseType:'H2'}];
  else if(path==='/api/status')body={status:'UP',consoleEnabled:true};
  else if(path.endsWith('/tables')){refreshed++;body=removed?[{id:12,schema:'APP',name:'NEW_TABLE'}]:[{id:11,schema:'APP',name:'OLD_TABLE'},{id:12,schema:'APP',name:'NEW_TABLE'}];}
  else if(path.endsWith('/mapping-workbook')){
   multipart=route.request().postDataBuffer()?.toString()??'';
   expect(route.request().headers()['content-type']).toContain('multipart/form-data');
   if(fail){await route.fulfill({status:400,json:{message:'BAU File Spec must contain a File Name column.'}});return;}
   removed=true;await route.fulfill({contentType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',body:'test workbook'});return;
  }else if(path.endsWith('/columns'))body=[];
  await route.fulfill({json:body});
 });
 await page.goto('/mapping');await page.getByLabel('Select all tables',{exact:true}).check();
 await page.getByLabel('Is a BAU mapping workbook available?',{exact:true}).selectOption('yes');
 await expect(page.getByText(/permanently removed from this connection after successful generation/)).toBeVisible();
 const upload=page.getByLabel('BAU mapping workbook (.xlsx, up to 5 MB)',{exact:true});
 await upload.setInputFiles({name:'invalid.txt',mimeType:'text/plain',buffer:Buffer.from('invalid')});
 await expect(page.getByRole('alert')).toContainText('Upload a BAU .xlsx workbook');
 const generate=page.getByRole('button',{name:'Generate mapping workbook',exact:true});await expect(generate).toBeDisabled();
 await upload.setInputFiles({name:'bau.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:Buffer.from('fixture')});
 await generate.click();await expect(page.getByRole('alert')).toContainText('BAU File Spec must contain a File Name column.');
 await expect(page.getByLabel('Select APP.OLD_TABLE',{exact:true})).toBeChecked();expect(refreshed).toBe(1);
 expect(multipart).toContain('name="bauFile"; filename="bau.xlsx"');expect(multipart).toContain('name="tableIds"');
 fail=false;const download=page.waitForEvent('download');await generate.click();await download;
 await expect(page.getByLabel('Select APP.OLD_TABLE',{exact:true})).toHaveCount(0);
 await expect(page.getByRole('status').filter({hasText:'Mapping workbook downloaded.'})).toContainText('Matching BAU registrations were permanently removed');expect(refreshed).toBe(2);
});

test('a BAU workbook can be generated without selecting new tables',async({page})=>{
 let multipart='';
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let body:any={};
  if(path==='/api/connections')body=[{id:1,name:'Source',schema:'APP',databaseType:'H2'}];
  else if(path==='/api/status')body={status:'UP'};
  else if(path.endsWith('/tables'))body=[];
  else if(path.endsWith('/mapping-workbook')){multipart=route.request().postDataBuffer()?.toString()??'';await route.fulfill({contentType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',body:'workbook'});return;}
  await route.fulfill({json:body});
 });
 await page.goto('/mapping');await page.getByLabel('Is a BAU mapping workbook available?',{exact:true}).selectOption('yes');
 await page.getByLabel('BAU mapping workbook (.xlsx, up to 5 MB)',{exact:true}).setInputFiles({name:'bau.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:Buffer.from('fixture')});
 const download=page.waitForEvent('download');await page.getByRole('button',{name:'Generate mapping workbook',exact:true}).click();await download;
 expect(multipart).toContain('name="bauFile"');expect(multipart).not.toContain('name="tableIds"');
});
