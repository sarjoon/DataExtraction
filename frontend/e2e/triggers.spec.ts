import { test, expect } from '@playwright/test';

test('screen registration, selection, workbook download and connection reset',async({page})=>{
  const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
  const screens=[{id:11,instrument:'Bond',name:'Portfolio Screen',source:'UPLOAD'},{id:12,instrument:'Bond',name:'Account Screen',source:'MANUAL'},{id:21,instrument:'Equity',name:'Equity Screen',source:'UPLOAD'}];
  const readable:{id:number;tableName:string;fieldName:string}[]=[];
  let selection:number[]=[];
  await page.route('**/api/**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname;let body:unknown={};
    if(path==='/api/connections')body=[{id:1,name:'Oracle A',databaseType:'ORACLE',schema:'APP'},{id:2,name:'Oracle B',databaseType:'ORACLE',schema:'APP'}];
    else if(path==='/api/status')body={status:'UP',database:'H2'};
    else if(path.endsWith('/tables'))body=[];
    else if(path.endsWith('/screens/readable-fields')){
      body=path.includes('/2/')?[]:readable;
    }else if(path.endsWith('/screens/11/readable-fields')){
      if(request.method()==='POST'){
        const fields=request.postDataJSON().fields as {tableName:string;fieldName:string}[];
        for(let i=readable.length-1;i>=0;i--)if(readable[i].tableName==='INSTRUMENT')readable.splice(i,1);
        for(const field of fields){expect(field.tableName).toBe('INSTRUMENT');readable.push({id:field.fieldName==='PORTFOLIO_ID'?101:103,...field});}
        body={added:fields.length,skipped:0,total:readable.length};
      }else body=[...['PORTFOLIO_ID','VALUE_D'].map(fieldName=>({tableName:'INSTRUMENT',fieldName,selectable:true,configured:readable.some(f=>f.tableName==='INSTRUMENT'&&f.fieldName===fieldName)})),{tableName:null,fieldName:'UNKNOWN_FIELD',selectable:false,configured:false}];
    }else if(path.endsWith('/screens/readable-fields/import')){
      expect(request.postData()).toContain('Table Name,Field Name');readable.push({id:102,tableName:'ACCOUNT',fieldName:'VALUE_D'});body={added:1,skipped:0,total:readable.length};
    }else if(path.endsWith('/screens/readable-fields/102/remove')){
      readable.splice(readable.findIndex(f=>f.id===102),1);body={};
    }
    else if(path.endsWith('/screens/workbook')){
      selection=request.postDataJSON().screenIds;
      await route.fulfill({contentType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',body:'workbook'});return;
    }else if(path.endsWith('/screens/import')){
      expect(request.postData()).toContain('Instrument,Screen Name');
      expect(request.postData()).toContain('Equity,Equity Screen');
      body={added:1,skipped:0,total:screens.length};
    }
    else if(path.endsWith('/screens')){
      if(request.method()==='POST'){
        expect(request.postDataJSON().instrument).toBe('Bond');expect(request.postDataJSON().names).toEqual(['New Screen']);screens.push({id:13,instrument:'Bond',name:'New Screen',source:'MANUAL'});
        body={added:1,skipped:0,total:screens.length};
      }else body=path.includes('/2/')?[]:screens;
    }
    await route.fulfill({json:body});
  });
  await page.goto('/triggers');
  await expect(page.getByRole('heading',{name:'Complete Call Trigger',exact:true})).toBeVisible();
  await page.getByRole('combobox',{name:'Instrument',exact:true}).selectOption('Bond');
  await expect(page.getByRole('cell',{name:'Portfolio Screen',exact:true})).toBeVisible();
  await expect(page.getByRole('cell',{name:'Equity Screen',exact:true})).toHaveCount(0);
  await page.getByLabel('Select Portfolio Screen',{exact:true}).check();
  await expect(page.getByRole('button',{name:'Generate workbook',exact:true})).toBeDisabled();
  await expect(page.getByRole('button',{name:'Add readable field',exact:true})).toHaveCount(0);
  await page.getByRole('combobox',{name:'Screen for readable fields',exact:true}).selectOption({label:'Bond / Portfolio Screen'});
  await page.getByRole('button',{name:'Load screen fields',exact:true}).click();
  await expect(page.getByLabel('Include Unknown.UNKNOWN_FIELD',{exact:true})).toBeDisabled();
  await page.getByLabel('Include INSTRUMENT.PORTFOLIO_ID',{exact:true}).check();
  await page.getByRole('button',{name:'Save screen field selection',exact:true}).click();
  await expect(page.getByRole('button',{name:'Generate workbook',exact:true})).toBeEnabled();
  await page.getByRole('button',{name:'Load screen fields',exact:true}).click();
  await expect(page.getByLabel('Include INSTRUMENT.PORTFOLIO_ID',{exact:true})).toBeChecked();
  await page.getByLabel('Select all screen fields',{exact:true}).check();
  await page.getByRole('button',{name:'Save screen field selection',exact:true}).click();
  await expect(page.getByLabel('Include INSTRUMENT.VALUE_D',{exact:true})).toBeChecked();
  await page.getByLabel('Include INSTRUMENT.VALUE_D',{exact:true}).uncheck();
  await page.getByRole('button',{name:'Save screen field selection',exact:true}).click();
  await expect(page.getByLabel('Include INSTRUMENT.VALUE_D',{exact:true})).not.toBeChecked();
  await page.getByLabel('Choose readable fields',{exact:true}).setInputFiles({name:'readable.csv',mimeType:'text/csv',buffer:Buffer.from('Table Name,Field Name\nACCOUNT,VALUE_D')});
  await page.getByRole('button',{name:'Upload readable fields',exact:true}).click();
  await expect(page.getByRole('button',{name:'Remove ACCOUNT.VALUE_D',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Remove ACCOUNT.VALUE_D',exact:true}).click();
  await expect(page.getByRole('button',{name:'Remove ACCOUNT.VALUE_D',exact:true})).toHaveCount(0);
  await page.getByLabel('Screen names',{exact:true}).fill('New Screen');
  await page.getByRole('button',{name:'Append screens',exact:true}).click();
  await expect(page.getByRole('cell',{name:'New Screen',exact:true})).toBeVisible();
  await page.getByLabel('Select Portfolio Screen',{exact:true}).check();
  const download=page.waitForEvent('download');
  await page.getByRole('button',{name:'Generate workbook',exact:true}).click();
  expect((await download).suggestedFilename()).toBe('Complete_Call_Trigger.xlsx');expect(selection).toEqual([11]);
  await page.getByLabel('Select all visible screens',{exact:true}).check();
  const allDownload=page.waitForEvent('download');
  await page.getByRole('button',{name:'Generate workbook',exact:true}).click();await allDownload;expect(selection).toEqual([11,12,13]);
  await page.getByRole('combobox',{name:'Instrument',exact:true}).selectOption('Equity');
  await expect(page.getByRole('cell',{name:'Equity Screen',exact:true})).toBeVisible();
  await expect(page.getByRole('cell',{name:'Portfolio Screen',exact:true})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'Generate workbook',exact:true})).toBeDisabled();
  await page.getByRole('combobox',{name:'Instrument',exact:true}).selectOption('Bond');
  await page.getByLabel('Instrument name for registration',{exact:true}).fill('');
  await page.getByLabel('Choose screen list',{exact:true}).setInputFiles({name:'screens.csv',mimeType:'text/csv',buffer:Buffer.from('Instrument,Screen Name\nBond,Portfolio Screen\nEquity,Equity Screen')});
  await page.getByRole('button',{name:'Upload & append',exact:true}).click();
  await expect(page.getByRole('status').filter({hasText:'screen(s) added'})).toBeVisible();
  await page.getByRole('combobox',{name:'Oracle connection',exact:true}).selectOption({label:'Oracle B · APP'});
  await expect(page.getByText('Select an instrument to view its screens.')).toBeVisible();
  await expect(page.getByRole('button',{name:'Generate workbook',exact:true})).toBeDisabled();
  expect(errors).toEqual([]);
});
