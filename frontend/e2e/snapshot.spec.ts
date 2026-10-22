import {test,expect} from '@playwright/test';

test('Snapshot configures exclusions, captures, compares, paginates and reopens history',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 let batches:{id:number;name:string;tables:string[]}[]=[];let finishComparison=false;
 let registered=['ITEMS'];let configs=[{tableName:'ITEMS',ignoredColumns:['AUDIT_TS']}];let captured=false;let compared=false;
 const report={id:'ITEMS_20261021_120000_001',timestamp:'20261021_120000_001',startedAt:'2026-10-21T04:00:00Z',completedAt:'2026-10-21T04:01:00Z',before:{name:'Before DB',schema:'PUBLIC'},after:{name:'After DB',schema:'PUBLIC'},tables:[{tableName:'ITEMS',primaryKeys:['ID','PART'],columns:['ID','PART','VALUE_N','AUDIT_TS'],ignoredColumns:['AUDIT_TS'],beforeCount:500000,afterCount:500000,beforeOnlyCount:12,afterOnlyCount:12,differenceCount:12,unchangedCount:499976,status:'Difference exists'}]};
 Object.assign(report,{batch:{id:91,name:'Release checks',tables:['ITEMS']},id:'Release_checks_20261021_120000_001'});
 await page.route('**/api/**',async route=>{
  const request=route.request(),url=new URL(request.url()),path=url.pathname;let body:unknown={};
  if(path==='/api/connections')body=[{id:1,name:'Before DB',databaseType:'H2',schema:'PUBLIC',readOnly:true},{id:2,name:'After DB',databaseType:'H2',schema:'PUBLIC',readOnly:false},{id:3,name:'Protected DB',databaseType:'H2',schema:'PUBLIC',readOnly:true}];
  else if(path==='/api/status')body={status:'UP',database:'H2'};
  else if(path==='/api/csrf')body={};
  else if(path.endsWith('/tables'))body=[{id:7,connectionId:1,schema:'PUBLIC',name:'EDAC_ONLY',source:'DATABASE'}];
  else if(path==='/api/snapshot/tables/1'){if(request.method()==='POST')registered=request.postDataJSON();body=registered;}
  else if(path==='/api/snapshot/tables/1/discover')body=['FOURTH','ITEMS','NOT_SELECTED','THIRD'];
  else if(path==='/api/snapshot/batches/1'){if(request.method()==='POST'){const input=request.postDataJSON();const batch={id:input.id??91,name:input.name,tables:input.tables};batches=[...batches.filter(b=>b.id!==batch.id),batch];body=batch;}else body=batches;}
  else if(path==='/api/snapshot/configuration/1'){if(request.method()==='POST')configs=request.postDataJSON();body=configs;}
  else if(path==='/api/snapshot/columns/1/ITEMS')body={tableName:'ITEMS',schema:'PUBLIC',primaryKeys:['ID','PART'],columns:[{name:'ID',typeName:'INTEGER'},{name:'PART',typeName:'VARCHAR'},{name:'VALUE_N',typeName:'DECIMAL'},{name:'AUDIT_TS',typeName:'TIMESTAMP'}]};
  else if(path==='/api/snapshot/inspect')body=[{tableName:'ITEMS',exists:true,recordCount:500000}];
  else if(path==='/api/snapshot/capture'){expect(request.postDataJSON()).toEqual({sourceId:1,targetId:2,tables:[],mode:'APPEND',batchId:91});captured=true;body={id:'capture',operation:'CAPTURE',status:'RUNNING',message:'Starting',processed:0};}
  else if(path==='/api/snapshot/compare'){compared=true;body={id:'compare',operation:'COMPARE',status:'RUNNING',message:'Starting',processed:0};}
  else if(path==='/api/snapshot/jobs/capture')body={id:'capture',operation:'CAPTURE',status:'COMPLETED',message:'Completed',processed:500000,reportId:null};
  else if(path==='/api/snapshot/jobs/compare')body={id:'compare',operation:'COMPARE',status:finishComparison?'COMPLETED':'RUNNING',message:finishComparison?'Completed':'Comparing matching records: ITEMS',processed:499988,beforeKeysRead:500000,afterKeysRead:500000,beforeOnly:12,afterOnly:12,reportId:finishComparison?report.id:null};
  else if(path==='/api/snapshot/history')body=[report];
  else if(path==='/api/snapshot/reports/'+report.id)body=report;
  else if(path.endsWith('/download')){await route.fulfill({contentType:'application/zip',headers:{'Content-Disposition':'attachment; filename="'+report.id+'.zip"'},body:'zip'});return;}
  else if(path.includes('/ITEMS/')){const offset=Number(url.searchParams.get('page')??0)*10;body={total:12,rows:Array.from({length:offset===0?10:2},(_,i)=>({primaryKey:{ID:String(offset+i+1),PART:'A'},values:{ID:String(offset+i+1),PART:'A',VALUE_N:'1',AUDIT_TS:null},changes:[{column:'VALUE_N',before:'1',after:'2'}]}))};}
  else throw new Error('Unexpected API request '+path);
  await route.fulfill({json:body});
 });
 await page.goto('/snapshot/tables');
 const navigation=page.getByRole('navigation',{name:'Main navigation'});
 await expect(navigation.locator('details').filter({has:page.locator('summary',{hasText:'EDAC Extraction'})}).getByRole('link',{name:'Table list',exact:false})).toBeVisible();
 await expect(navigation.locator('details').filter({has:page.locator('summary',{hasText:'Call Trigger'})}).getByRole('link',{name:'Complete Call Trigger',exact:true})).toBeVisible();
 await expect(navigation.getByRole('link',{name:'Database connections',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'Discover tables',exact:true}).click();
 await expect(page.getByRole('checkbox',{name:'Register ITEMS',exact:true})).toBeChecked();
 await page.getByRole('checkbox',{name:'Register NOT_SELECTED',exact:true}).check();
 await page.getByRole('checkbox',{name:'Register THIRD',exact:true}).check();
 await expect(page.getByRole('checkbox',{name:'Register FOURTH',exact:true})).toBeEnabled();
 await page.getByRole('checkbox',{name:'Register FOURTH',exact:true}).check();
 await page.getByRole('button',{name:'Save selected tables',exact:true}).click();
 await expect(page.getByRole('status').filter({hasText:'Snapshot table selections saved'})).toBeVisible();
 expect(registered).toEqual(['ITEMS','NOT_SELECTED','THIRD','FOURTH']);
 await page.reload();await expect(page.getByRole('checkbox',{name:'Register NOT_SELECTED',exact:true})).toBeChecked();
 await page.getByRole('navigation',{name:'Snapshot navigation'}).getByRole('link',{name:'Configuration',exact:true}).click();
 const table=page.getByRole('combobox',{name:'Table name',exact:true});
 await expect(table.getByRole('option',{name:'EDAC_ONLY',exact:true})).toHaveCount(0);
 await expect(table.getByRole('option',{name:'NOT_SELECTED',exact:true})).toHaveCount(1);
 await table.selectOption('ITEMS');await page.getByRole('button',{name:'Load columns',exact:true}).click();
 await expect(page.getByRole('checkbox',{name:'ID INTEGER',exact:true})).toHaveCount(0);
 await expect(page.getByRole('checkbox',{name:/ID INTEGER/})).toBeDisabled();
 await expect(page.getByRole('checkbox',{name:/AUDIT_TS/})).toBeChecked();
 await page.getByRole('button',{name:'Save table configuration',exact:true}).click();await expect(page.getByRole('status').filter({hasText:'Snapshot configuration saved'})).toBeVisible();
 await page.getByRole('navigation',{name:'Snapshot navigation'}).getByRole('link',{name:'Batches',exact:true}).click();
 await page.getByLabel('Batch name',{exact:true}).fill('Release checks');
 await page.getByRole('checkbox',{name:'Assign ITEMS',exact:true}).check();
 await page.getByRole('button',{name:'Create batch',exact:true}).click();
 await expect(page.getByRole('status').filter({hasText:'Snapshot batch saved'})).toBeVisible();
 await page.getByRole('navigation',{name:'Snapshot navigation'}).getByRole('link',{name:'Take snapshot',exact:true}).click();
 await expect(page.getByRole('combobox',{name:'Destination connection',exact:true}).getByRole('option',{name:/Protected DB/})).toHaveJSProperty('disabled',true);
 await page.getByRole('combobox',{name:'Execution batch',exact:true}).selectOption({label:'Release checks (1 tables)'});
 await expect(page.getByRole('checkbox',{name:/ITEMS/})).toBeChecked();
 await expect(page.getByRole('checkbox',{name:/ITEMS/})).toBeDisabled();
 await page.getByRole('button',{name:'Inspect destination'}).click();
 await expect(page.getByRole('button',{name:'Take snapshot',exact:true})).toBeDisabled();
 await page.getByRole('radio',{name:'Append and update duplicate primary keys'}).check();await page.getByRole('button',{name:'Take snapshot',exact:true}).click();
 await expect(page.getByRole('status').filter({hasText:'Snapshot captured successfully'})).toBeVisible();expect(captured).toBe(true);
 await page.getByRole('navigation',{name:'Snapshot navigation'}).getByRole('link',{name:'Compare',exact:true}).click();
 await expect(page.getByRole('combobox',{name:'After Modification',exact:true}).getByRole('option',{name:/Protected DB/})).toHaveJSProperty('disabled',false);
 await page.getByRole('combobox',{name:'Execution batch',exact:true}).selectOption({label:'Release checks (1 tables)'});await page.getByRole('button',{name:'Compare tables'}).click();
 await expect(page.getByText('Across selected tables: 499,988 record pairs compared',{exact:true})).toBeVisible();
 await expect(page.getByText('Keys read — Before: 500,000 · After: 500,000',{exact:true})).toBeVisible();
 await expect(page.getByText('Records only Before: 12 · Records only After: 12',{exact:true})).toBeVisible();
 finishComparison=true;
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
