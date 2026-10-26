import {test,expect} from '@playwright/test';

const pageData=(columns:string[],rows:(string|null)[][])=>({columns,rows,page:0,size:100,hasMore:false,message:null});
test('User profile matrix accepts free text and drills down with stale responses ignored',async({page})=>{
 const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message));
 let releaseReport:()=>void=()=>{},reportStarted=false;
 const delayedReport=new Promise<void>(resolve=>releaseReport=resolve);
 const main=pageData(['USER_CODE','ACCESS_SOURCE','CHANNEL_CODE','CHANNEL_PROFILE','PROFILE_TYPE','PROFILE_CODE'],
  ['REPORT','DATA','FUNCTION_SECURITY','FORMAT','SCREEN','MENU','EXTERNAL_SERVICE'].map(type=>['USER001','DIRECT_USER','GUI','CP_OPER',type,'P_'+type]));
 await page.route('**/api/**',async route=>{
  const request=route.request(),url=new URL(request.url()),path=url.pathname;let body:unknown={};
  if(path==='/api/connections')body=[{id:1,name:'Oracle source',databaseType:'ORACLE',schema:'APP'},{id:2,name:'Sybase source',databaseType:'SYBASE',schema:'app_owner'},{id:3,name:'H2 source',databaseType:'H2',schema:'PUBLIC'}];
  else if(path==='/api/status')body={status:'UP',database:'H2',migrationVersion:'17'};
  else if(path.endsWith('/tables'))body=[];
  else if(path==='/api/csrf')body={};
  else if(path.endsWith('/matrix')){expect(url.searchParams.get('userCode')).toBeTruthy();body={...main,rows:main.rows.map(row=>[url.searchParams.get('userCode'),...row.slice(1)])};}
  else if(path.endsWith('/profiles/REPORT')){expect(url.searchParams.get('profileCode')).toBe('P_REPORT');reportStarted=true;await delayedReport;body=pageData(['REPORT_PROFILE','REPORT_CODE','REPORT_DENOM'],[['P_REPORT','R_OLD','Old report response']]);}
  else if(path.endsWith('/profiles/DATA'))body=pageData(['DATA_PROFILE','DATA_SECU_PROFILE','AUTH_UPDATE','AUTH_DELETE'],[['P_DATA','DATA_SEC_OPER','1','0']]);
  else if(path.endsWith('/profiles/FUNCTION_SECURITY'))body=pageData(['FSP_CODE','FUNCTION','ENTITY','TYPE','SUBTYPE','MIN_STATUS','MAX_STATUS','CREATE_FLAG','UPDATE_FLAG','DELETE_FLAG','RISK_VIEW_FLAG','REALTIME_FLAG','VIEW_FLAG','VISIBLE_FLAG'],[['P_FUNCTION_SECURITY','Book','operation','DEPOSIT','FIXED','Draft','Validated','1','1','0','1','1','1','1']]);
  else if(path.endsWith('/profiles/FORMAT'))body=pageData(['FORMAT_PROFILE','FORMAT','FORMAT_NAME','FUNCTION','NATURE','RANK'],[['P_FORMAT','F_DEPOSIT','Deposit format','Book','Display','1']]);
  else if(path.endsWith('/profiles/FORMAT/items')){expect(url.searchParams.get('item')).toBe('F_DEPOSIT');body=pageData(['FORMAT_CODE','ELEMENT_CODE','ELEMENT_NAME','ELEMENT_DENOM','ELEMENT_DATATYPE','ELEMENT_RANK','DISPLAY_COLUMN','DISPLAY_FORMAT','FIXED_COLUMN','CONSOLIDATION','SUB_TOTAL','TOTALISER','COLUMN_WIDTH'],[['F_DEPOSIT','quantity_n','Quantity','Trade quantity','decimal','1','1',null,'0','0','0',null,'120']]);}
  else if(path.endsWith('/profiles/SCREEN'))body=pageData(['SCREEN_PROFILE','SCREEN_NAME','ENTITY','NATURE_ATTRIB','TYPE_ATTRIB','SUBTYPE_ATTRIB','TYPE_CODE','SUBTYPE_CODE','FUNCTION_CODE','REPORT_CODE'],[['P_SCREEN','Deposit Entry','operation','nature_e','type_id','subtype_id','DEPOSIT','FIXED','Book','R_OPER']]);
  else if(path.endsWith('/profiles/SCREEN/items')){expect(url.searchParams.get('item')).toBe('Deposit Entry');body=pageData(['SCREEN_NAME','ENTITY','PANEL','PANEL_DENOM','ATTRIBUTE','NATURE','EDITABLE','MANDATORY','PARENT_PANEL','PARENT_PANEL_DENOM'],[['Deposit Entry','operation','quantity_panel','Quantity input','quantity_n','Input','1','1','main_panel','Operation details']]);}
  else throw new Error('Unexpected report request '+path);
  if(path.includes('/profiles/'))expect(url.searchParams.get('userCode')).toBe('USER001');
  await route.fulfill({json:body});
 });
 await page.goto('/reports/user-profile-matrix');await expect(page.getByRole('heading',{name:'User profile matrix',exact:true})).toBeVisible();
 const userInput=page.getByRole('textbox',{name:'Report user code'});
 await expect(userInput).toHaveValue('');await expect(page.locator('input[type=file]')).toHaveCount(0);
 await expect(page.getByRole('button',{name:'Load matrix',exact:true})).toBeDisabled();
 await userInput.fill('USER001');
 await page.getByRole('button',{name:'Load matrix',exact:true}).click();
 const mainGrid=page.getByRole('region',{name:'User profile matrix',exact:true});
 await mainGrid.getByRole('button',{name:'P_REPORT',exact:true}).click();await expect.poll(()=>reportStarted).toBe(true);
 await mainGrid.getByRole('button',{name:'P_DATA',exact:true}).click();await expect(page.getByRole('heading',{name:'Data security details'})).toBeVisible();
 const oldResponse=page.waitForResponse(response=>new URL(response.url()).pathname.endsWith('/profiles/REPORT'));
 releaseReport();await (await oldResponse).finished();
 await page.evaluate(()=>new Promise<void>(resolve=>requestAnimationFrame(()=>resolve())));
 await expect(page.getByRole('heading',{name:'Report profile details',exact:true})).toHaveCount(0);
 await mainGrid.getByRole('button',{name:'P_FUNCTION_SECURITY',exact:true}).click();await expect(page.getByRole('cell',{name:'Validated',exact:true})).toBeVisible();
 await mainGrid.getByRole('button',{name:'P_FORMAT',exact:true}).click();await page.getByRole('button',{name:'F_DEPOSIT',exact:true}).click();await expect(page.getByRole('heading',{name:'Format elements',exact:true})).toBeVisible();
 await mainGrid.getByRole('button',{name:'P_SCREEN',exact:true}).click();await expect(page.getByRole('heading',{name:'Format elements',exact:true})).toHaveCount(0);
 await page.getByRole('button',{name:'Deposit Entry',exact:true}).click();await expect(page.getByRole('columnheader',{name:'PARENT_PANEL_DENOM',exact:true})).toBeVisible();
 await page.screenshot({path:'test-results/user-profile-matrix.png',fullPage:true});
 await mainGrid.getByRole('button',{name:'P_MENU',exact:true}).click();await expect(page.getByText('No detail query configured for MENU.',{exact:true})).toBeVisible();
 await expect(page.getByRole('heading',{name:'Screen panels',exact:true})).toHaveCount(0);
 await userInput.fill('USER+02 @company');await expect(page.getByRole('region',{name:'User profile matrix',exact:true})).toHaveCount(0);
 await page.getByRole('button',{name:'Load matrix',exact:true}).click();await expect(mainGrid).toContainText('USER+02 @company');
 await page.getByRole('combobox',{name:'Report database connection'}).selectOption({label:'Sybase source · SYBASE · app_owner'});
 await expect(mainGrid).toHaveCount(0);
 await userInput.fill('ASE_USER');await userInput.press('Enter');await expect(mainGrid).toContainText('ASE_USER');
 await page.getByRole('combobox',{name:'Report database connection'}).selectOption({label:'H2 source · H2 · PUBLIC'});
 await expect(mainGrid).toHaveCount(0);await userInput.fill('H2_USER');await page.getByRole('button',{name:'Load matrix',exact:true}).click();await expect(mainGrid).toContainText('H2_USER');
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 expect(errors).toEqual([]);
});
