import {test,expect} from '@playwright/test';

test('Schema Comparison supports Sybase, named differences, table drill-down and saved Excel reports',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));let compared=false;
 const id='Schema_20261023_103000_001_abcd1234';
 const report={id,timestamp:'20261023_103000_001',startedAt:'2026-10-23T02:30:00Z',completedAt:'2026-10-23T02:31:00Z',before:{name:'Company ASE',databaseType:'SYBASE',database:'AAA',schema:'dbo'},after:{name:'Oracle After',databaseType:'ORACLE',database:'ORCL',schema:'APP'},status:'Differences exist',includeRowCounts:true,normalizeTypes:true,notes:['Object counts include only visible schema objects.'],totals:{objectCounts:5,objectDifferences:2,rowCounts:1,structureSummary:1,structureDifferences:12}};
 await page.route('**/api/**',async route=>{
  const request=route.request(),url=new URL(request.url()),path=url.pathname;let body:unknown={};
  if(path==='/api/connections')body=[{id:1,name:'Company ASE',databaseType:'SYBASE',schema:'dbo',readOnly:true},{id:2,name:'Oracle After',databaseType:'ORACLE',schema:'APP',readOnly:true}];
  else if(path==='/api/status')body={status:'UP',database:'H2',migrationVersion:'13'};
  else if(path==='/api/csrf')body={};
  else if(path.endsWith('/tables'))body=[];
  else if(path==='/api/snapshot/schema/compare'){expect(request.postDataJSON()).toEqual({beforeId:1,afterId:2,includeRowCounts:true,normalizeTypes:true});compared=true;body={id,status:'RUNNING',message:'Reading Before metadata',beforeTablesRead:0,afterTablesRead:0,reportId:null};}
  else if(path==='/api/snapshot/schema/jobs/'+id)body={id,status:'COMPLETED',message:'Completed',beforeTablesRead:120,afterTablesRead:121,reportId:id};
  else if(path==='/api/snapshot/schema/history')body=[report];
  else if(path==='/api/snapshot/schema/reports/'+id)body=report;
  else if(path.endsWith('/download')){await route.fulfill({contentType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',headers:{'Content-Disposition':'attachment; filename='+id+'.xlsx'},body:'workbook'});return;}
  else if(path.endsWith('/objectCounts'))body={rows:[{objectType:'Tables',beforeCount:120,afterCount:121,difference:1,status:'Count mismatch'},{objectType:'Views',beforeCount:24,afterCount:24,difference:0,status:'Names differ'}],total:2};
  else if(path.endsWith('/structureSummary'))body={rows:[{tableName:'CUSTOMER',beforeColumns:100,afterColumns:100,differenceCount:12,status:'Structure differs',coverage:'Compared'}],total:1};
  else if(path.endsWith('/structureDifferences')){expect(url.searchParams.get('table')).toBe('CUSTOMER');const offset=Number(url.searchParams.get('page'))*10;body={rows:Array.from({length:offset?2:10},(_,i)=>({tableName:'CUSTOMER',columnOrObject:'FIELD_'+(offset+i),property:'Length',before:'80',after:'120',status:'Changed'})),total:12};}
  else throw new Error('Unexpected schema API request '+path);
  await route.fulfill({json:body});
 });
 await page.goto('/snapshot/schema');await expect(page.getByRole('heading',{name:'Schema Comparison',exact:true})).toBeVisible();
 await expect(page.getByRole('combobox',{name:'Schema Before connection'})).toContainText('Company ASE');
 await page.getByRole('checkbox',{name:'Include exact table row counts'}).check();await page.getByRole('button',{name:'Compare schemas'}).click();
 await expect(page.getByRole('cell',{name:'Names differ',exact:true})).toBeVisible();expect(compared).toBe(true);
 await page.getByRole('tab',{name:/Table structures/}).click();await page.getByRole('button',{name:'View changes',exact:true}).click();
 await expect(page.getByText('Changes for CUSTOMER',{exact:false})).toBeVisible();await expect(page.getByRole('cell',{name:'FIELD_0',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'Next',exact:true}).click();await expect(page.getByRole('cell',{name:'FIELD_10',exact:true})).toBeVisible();
 const download=page.waitForEvent('download');await page.getByRole('link',{name:'Download Excel',exact:true}).click();expect((await download).suggestedFilename()).toBe(id+'.xlsx');
 await page.getByRole('link',{name:'Schema history',exact:true}).click();await expect(page.getByRole('cell',{name:'Company ASE · dbo',exact:true})).toBeVisible();
 await page.getByRole('link',{name:'Render',exact:true}).click();await expect(page.getByRole('cell',{name:'Count mismatch',exact:true})).toBeVisible();
 await page.screenshot({path:'test-results/schema-comparison.png',fullPage:true});expect(errors).toEqual([]);
});

test('Sybase connection form uses ASE database and owner without bundling a driver',async({page})=>{
 await page.route('**/api/**',async route=>{const path=new URL(route.request().url()).pathname;await route.fulfill({json:path==='/api/connections'?[]:path==='/api/status'?{status:'UP',database:'H2'}:{}});});
 await page.goto('/connections');await page.getByRole('combobox',{name:'Database type',exact:true}).selectOption('SYBASE');
 await expect(page.getByRole('combobox',{name:'Connection type',exact:true})).toHaveValue('ASE');await expect(page.getByLabel('Port',{exact:true})).toHaveValue('5000');
 await expect(page.getByLabel('Schema / owner',{exact:true})).toHaveValue('dbo');await expect(page.getByLabel('Sybase password',{exact:true})).toBeVisible();
 await expect(page.getByText(/edac.sybase.driver-path=/)).toBeVisible();
});
