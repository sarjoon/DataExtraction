import {test,expect} from '@playwright/test';

test('Snapshot configures exclusions, captures, compares, paginates and reopens history',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 let configs=[{tableName:'ITEMS',ignoredColumns:['AUDIT_TS']}];let captured=false;let compared=false;
 const report={id:'ITEMS_20261021_120000_001',timestamp:'20261021_120000_001',startedAt:'2026-10-21T04:00:00Z',completedAt:'2026-10-21T04:01:00Z',before:{name:'Before DB',schema:'PUBLIC'},after:{name:'After DB',schema:'PUBLIC'},tables:[{tableName:'ITEMS',primaryKeys:['ID','PART'],columns:['ID','PART','VALUE_N','AUDIT_TS'],ignoredColumns:['AUDIT_TS'],beforeCount:500000,afterCount:500000,beforeOnlyCount:12,afterOnlyCount:12,differenceCount:12,unchangedCount:499976,status:'Difference exists'}]};
 await page.route('**/api/**',async route=>{
  const request=route.request(),url=new URL(request.url()),path=url.pathname;let body:unknown={};
  if(path==='/api/connections')body=[{id:1,name:'Before DB',databaseType:'H2',schema:'PUBLIC'},{id:2,name:'After DB',databaseType:'H2',schema:'PUBLIC'}];
  else if(path==='/api/status')body={status:'UP',database:'H2'};
  else if(path==='/api/csrf')body={};
  else if(path.endsWith('/tables'))body=[];
  else if(path==='/api/snapshot/configuration/1'){if(request.method()==='POST')configs=request.postDataJSON();body=configs;}
  else if(path==='/api/snapshot/columns/1/ITEMS')body={tableName:'ITEMS',schema:'PUBLIC',primaryKeys:['ID','PART'],columns:[{name:'ID',typeName:'INTEGER'},{name:'PART',typeName:'VARCHAR'},{name:'VALUE_N',typeName:'DECIMAL'},{name:'AUDIT_TS',typeName:'TIMESTAMP'}]};
  else if(path==='/api/snapshot/inspect')body=[{tableName:'ITEMS',exists:true,recordCount:500000}];
  else if(path==='/api/snapshot/capture'){expect(request.postDataJSON()).toEqual({sourceId:1,targetId:2,tables:['ITEMS'],mode:'APPEND'});captured=true;body={id:'capture',operation:'CAPTURE',status:'RUNNING',message:'Starting',processed:0};}
  else if(path==='/api/snapshot/compare'){compared=true;body={id:'compare',operation:'COMPARE',status:'RUNNING',message:'Starting',processed:0};}
  else if(path==='/api/snapshot/jobs/capture')body={id:'capture',operation:'CAPTURE',status:'COMPLETED',message:'Completed',processed:500000,reportId:null};
  else if(path==='/api/snapshot/jobs/compare')body={id:'compare',operation:'COMPARE',status:'COMPLETED',message:'Completed',processed:1000000,reportId:report.id};
  else if(path==='/api/snapshot/history')body=[report];
  else if(path==='/api/snapshot/reports/'+report.id)body=report;
  else if(path.endsWith('/download')){await route.fulfill({contentType:'application/zip',headers:{'Content-Disposition':'attachment; filename="'+report.id+'.zip"'},body:'zip'});return;}
  else if(path.includes('/ITEMS/')){const offset=Number(url.searchParams.get('page')??0)*10;body={total:12,rows:Array.from({length:offset===0?10:2},(_,i)=>({primaryKey:{ID:String(offset+i+1),PART:'A'},values:{ID:String(offset+i+1),PART:'A',VALUE_N:'1',AUDIT_TS:null},changes:[{column:'VALUE_N',before:'1',after:'2'}]}))};}
  else throw new Error('Unexpected API request '+path);
  await route.fulfill({json:body});
 });
 await page.goto('/snapshot/configuration');
 await page.getByLabel('Table name',{exact:true}).fill('ITEMS');await page.getByRole('button',{name:'Load columns',exact:true}).click();
 await expect(page.getByRole('checkbox',{name:'ID INTEGER',exact:true})).toHaveCount(0);
 await expect(page.getByRole('checkbox',{name:/ID INTEGER/})).toBeDisabled();
 await expect(page.getByRole('checkbox',{name:/AUDIT_TS/})).toBeChecked();
 await page.getByRole('button',{name:'Save table configuration',exact:true}).click();await expect(page.getByRole('status').filter({hasText:'Snapshot configuration saved'})).toBeVisible();
 await page.getByRole('navigation',{name:'Snapshot navigation'}).getByRole('link',{name:'Take snapshot',exact:true}).click();
 await page.getByRole('checkbox',{name:/ITEMS/}).check();await page.getByRole('button',{name:'Inspect destination'}).click();
 await expect(page.getByRole('button',{name:'Take snapshot',exact:true})).toBeDisabled();
 await page.getByRole('radio',{name:'Append and update duplicate primary keys'}).check();await page.getByRole('button',{name:'Take snapshot',exact:true}).click();
 await expect(page.getByRole('status').filter({hasText:'Snapshot captured successfully'})).toBeVisible();expect(captured).toBe(true);
 await page.getByRole('navigation',{name:'Snapshot navigation'}).getByRole('link',{name:'Compare',exact:true}).click();
 await page.getByRole('checkbox',{name:/ITEMS/}).check();await page.getByRole('button',{name:'Compare tables'}).click();
 await expect(page.getByRole('cell',{name:'Difference exists',exact:true})).toBeVisible();expect(compared).toBe(true);
 await expect(page.getByRole('img',{name:/499976 unchanged/})).toBeVisible();
 await page.screenshot({path:'../outputs/snapshot-verification/dashboard.png',fullPage:true});
 await page.getByRole('link',{name:'View differences'}).click();await expect(page.getByRole('tab',{name:'Summary',exact:true})).toHaveAttribute('aria-selected','true');
 await page.getByRole('tab',{name:'Records Only in Before Modification',exact:true}).click();await expect(page.locator('.result-grid tbody tr')).toHaveCount(10);
 await page.getByRole('button',{name:'Next',exact:true}).click();await expect(page.locator('.result-grid tbody tr')).toHaveCount(2);
 await page.getByRole('tab',{name:'Records Only in After Modification',exact:true}).click();await expect(page.locator('.result-grid tbody tr')).toHaveCount(10);
 await page.getByRole('tab',{name:'Differences',exact:true}).click();await page.getByRole('button',{name:'Expand differences',exact:true}).first().click();
 await expect(page.getByRole('dialog')).toContainText('VALUE_N');await expect(page.getByRole('dialog')).toContainText('Before Modification');
 await page.screenshot({path:'../outputs/snapshot-verification/differences.png',fullPage:true});
 await page.getByRole('button',{name:'Close',exact:true}).click();await expect(page.getByRole('dialog')).toHaveCount(0);
 await page.getByRole('navigation',{name:'Snapshot navigation'}).getByRole('link',{name:'History',exact:true}).click();
 await expect(page.getByRole('cell',{name:report.id,exact:true})).toBeVisible();const download=page.waitForEvent('download');await page.getByRole('link',{name:'Download ZIP',exact:true}).click();expect((await download).suggestedFilename()).toBe(report.id+'.zip');
 await page.getByRole('link',{name:'Render',exact:true}).click();await expect(page.getByRole('cell',{name:'ITEMS',exact:true})).toBeVisible();expect(errors).toEqual([]);
});
