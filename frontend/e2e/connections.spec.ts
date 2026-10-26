import {test,expect} from '@playwright/test';

test('editing a connection saves its schema while retaining a blank password input',async({page})=>{
 let saved=false;
 let profile={id:1,name:'Oracle source',databaseType:'ORACLE',host:'oracle.example',port:1521,connectionType:'SERVICE_NAME',databaseName:'ORCL',username:'APP_USER',schema:'OLD_SCHEMA',jdbcUrl:'jdbc:oracle:thin:@//oracle.example:1521/ORCL',readOnly:true,passwordSaved:true};
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let body:unknown={};
  if(path==='/api/connections')body=[profile];
  else if(path==='/api/connections/1'&&route.request().method()==='POST'){
   const input=route.request().postDataJSON();expect(input.schema).toBe('NEW_SCHEMA');expect(input.password).toBe('');
   profile={...profile,...input};saved=true;body=profile;
  }else if(path.endsWith('/tables'))body=[];
  else if(path==='/api/status')body={status:'UP',database:'H2'};
  await route.fulfill({json:body});
 });
 await page.goto('/connections');await page.getByRole('button',{name:'Edit connection',exact:true}).click();
 await page.getByLabel('Schema / owner',{exact:true}).fill('NEW_SCHEMA');
 await page.getByRole('button',{name:'Update connection',exact:true}).click();
 await expect(page.getByText(/saved in H2 with schema \/ owner NEW_SCHEMA/)).toBeVisible();expect(saved).toBe(true);
 await page.reload();await expect(page.getByRole('button',{name:/Oracle source/})).toContainText('NEW_SCHEMA');
 await page.getByRole('button',{name:'Edit connection',exact:true}).click();await expect(page.getByLabel('Schema / owner',{exact:true})).toHaveValue('NEW_SCHEMA');
});
