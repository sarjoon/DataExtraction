import {test,expect} from '@playwright/test';

test('table file options support manual entry, discovery, upload and per-table editing',async({page})=>{
 let rows:any[]=[];let uploaded=false;let edits=0;
 await page.route('**/api/**',async route=>{
  const request=route.request(),path=new URL(request.url()).pathname;let body:unknown={};
  if(path==='/api/connections')body=[{id:1,name:'EDAC source',schema:'APP',databaseType:'ORACLE'}];
  else if(path==='/api/status')body={status:'UP',database:'H2',migrationVersion:'14'};
  else if(path.endsWith('/tables')&&request.method()==='GET')body=rows;
  else if(path.endsWith('/tables')&&request.method()==='POST'){
   expect(request.postDataJSON()).toEqual({names:['CUSTOMER'],fileType:'Master',loadStrategy:'Full',updateFrequency:'Weekly'});
   rows.push({id:1,connectionId:1,schema:'APP',name:'CUSTOMER',source:'MANUAL',fileType:'Master',loadStrategy:'Full',updateFrequency:'Weekly'});body={added:1,skipped:0,total:rows.length};
  }else if(path.endsWith('/discover')){
   expect(request.postDataJSON()).toEqual({fileType:'Transaction',loadStrategy:'Delta',updateFrequency:'Daily'});
   rows.push({id:2,connectionId:1,schema:'APP',name:'PAYMENT',source:'DATABASE',fileType:'Transaction',loadStrategy:'Delta',updateFrequency:'Daily'});body={added:1,skipped:0,total:rows.length};
  }else if(path.endsWith('/import')){
   uploaded=true;rows.push({id:3,connectionId:1,schema:'APP',name:'CONFIG',source:'UPLOAD',fileType:'Parameter',loadStrategy:'Full',updateFrequency:'Weekly'});body={added:1,skipped:0,total:rows.length};
  }else if(path.endsWith('/1/options')){
   expect(request.postDataJSON()).toEqual({fileType:'Tech Recon',loadStrategy:'Full',updateFrequency:edits++===0?'Weekly':'Daily'});rows[0]={...rows[0],...request.postDataJSON()};body=rows[0];
  }
  await route.fulfill({json:body});
 });
 await page.goto('/tables');
 await page.getByLabel('Table names',{exact:true}).fill('CUSTOMER');
 await expect(page.getByRole('button',{name:'Append tables',exact:true})).toBeDisabled();
 await page.getByRole('combobox',{name:'Manual Update Frequency',exact:true}).selectOption('Weekly');
 await page.getByRole('combobox',{name:'Manual File Type',exact:true}).selectOption('Master');await page.getByRole('combobox',{name:'Manual Load Strategy',exact:true}).selectOption('Full');
 await page.getByRole('button',{name:'Append tables',exact:true}).click();await expect(page.getByLabel('File Type for APP.CUSTOMER',{exact:true})).toHaveValue('Master');
 await page.getByLabel('File Type for APP.CUSTOMER',{exact:true}).selectOption('Tech Recon');await expect(page.getByRole('status')).toContainText('File options saved');
 await page.getByLabel('Update Frequency for APP.CUSTOMER',{exact:true}).selectOption('Daily');
 await expect(page.getByLabel('Update Frequency for APP.CUSTOMER',{exact:true})).toBeEnabled();
 await page.getByRole('combobox',{name:'Discovery File Type',exact:true}).selectOption('Transaction');await page.getByRole('combobox',{name:'Discovery Load Strategy',exact:true}).selectOption('Delta');
 await page.getByRole('button',{name:'Discover & append',exact:true}).click();await expect(page.getByLabel('Load Strategy for APP.PAYMENT',{exact:true})).toHaveValue('Delta');
 await page.locator('input[type=file]').setInputFiles({name:'tables.csv',mimeType:'text/csv',buffer:Buffer.from('Table Name,Update Frequency,File Type,Load Strategy\nCONFIG,Weekly,Parameter,Full')});
 await page.getByRole('button',{name:'Upload & append',exact:true}).click();await expect(page.getByLabel('File Type for APP.CONFIG',{exact:true})).toHaveValue('Parameter');expect(uploaded).toBe(true);
 await page.reload();await expect(page.getByLabel('File Type for APP.CUSTOMER',{exact:true})).toHaveValue('Tech Recon');
 await expect(page.getByLabel('Update Frequency for APP.CUSTOMER',{exact:true})).toHaveValue('Daily');
 await page.screenshot({path:'test-results/table-file-options.png',fullPage:true});
});
