import { test, expect } from '@playwright/test';

test('readable frame shows only selected screen fields and scrolls after ten records',async({page})=>{
  const fields=[...Array.from({length:25},(_,i)=>({id:i+1,screenName:'Alpha Screen',fieldName:`alpha_field_${i+1}`})),
    ...Array.from({length:4},(_,i)=>({id:100+i,screenName:'Beta Screen',fieldName:`beta_field_${i+1}`})),
    {id:200,screenName:'Not In Dropdown',fieldName:'hidden_field'}];
  await page.route('**/api/**',async route=>{
    const path=new URL(route.request().url()).pathname;let body:unknown={};
    if(path==='/api/connections')body=[{id:1,name:'Oracle',databaseType:'ORACLE',schema:'APP'}];
    else if(path==='/api/status')body={status:'UP',database:'H2'};
    else if(path.endsWith('/tables'))body=[];
    else if(path.endsWith('/screens/readable-fields'))body=fields;
    else if(path.endsWith('/screens/11/readable-fields'))body=Array.from({length:30},(_,i)=>({screenName:'Alpha Screen',fieldName:`alpha_field_${i+1}`,selectable:true,configured:i<25}));
    else if(path.endsWith('/screens'))body=[{id:11,instrument:'Bond',name:'Alpha Screen',source:'UPLOAD'},{id:12,instrument:'Bond',name:'Beta Screen',source:'UPLOAD'}];
    await route.fulfill({json:body});
  });
  await page.goto('/triggers');
  const frame=page.getByRole('region',{name:'Readable fields',exact:true});
  const saved=page.getByRole('region',{name:'Saved readable fields',exact:true});
  const screen=page.getByRole('combobox',{name:'Screen for readable fields',exact:true});
  await expect(saved).toContainText('Select a screen above');
  await expect(saved.getByText('hidden_field',{exact:true})).toHaveCount(0);
  await screen.selectOption({label:'Alpha Screen'});
  await expect(saved.locator('tbody tr')).toHaveCount(25);
  await expect(saved.getByText('beta_field_1',{exact:true})).toHaveCount(0);
  const metrics=await saved.evaluate(element=>{
    const box=element.getBoundingClientRect();const header=element.querySelector('thead')!.getBoundingClientRect();
    return {height:element.clientHeight,scrollHeight:element.scrollHeight,border:parseFloat(getComputedStyle(element.closest('.readable-frame')!).borderTopWidth),
      fullRows:[...element.querySelectorAll('tbody tr')].filter(row=>{const r=row.getBoundingClientRect();return r.top>=header.bottom-1 && r.bottom<=box.bottom+1;}).length};
  });
  expect(metrics.height).toBeGreaterThanOrEqual(528);expect(metrics.fullRows).toBeGreaterThanOrEqual(10);
  expect(metrics.scrollHeight).toBeGreaterThan(metrics.height);expect(metrics.border).toBeGreaterThanOrEqual(2);
  await frame.screenshot({path:'../outputs/readable-fields-verification/frame.png'});
  await saved.evaluate(element=>{element.scrollTop=element.scrollHeight;});
  expect(await saved.evaluate(element=>element.scrollTop)).toBeGreaterThan(0);
  await page.setViewportSize({width:900,height:1100});
  expect(await saved.evaluate(element=>element.scrollWidth>element.clientWidth)).toBe(true);
  await page.getByRole('button',{name:'Load screen fields',exact:true}).click();
  const query=page.getByRole('region',{name:'Screen query fields',exact:true});
  await expect(query.locator('tbody tr')).toHaveCount(30);
  expect(await query.evaluate(element=>element.scrollHeight>element.clientHeight)).toBe(true);
  await screen.selectOption({label:'Beta Screen'});
  await expect(query).toHaveCount(0);await expect(saved.locator('tbody tr')).toHaveCount(4);
  await expect(saved.getByText('alpha_field_1',{exact:true})).toHaveCount(0);
  await expect(saved.getByText('hidden_field',{exact:true})).toHaveCount(0);
  await screen.selectOption({label:'Select a screen'});
  await expect(saved).toContainText('Select a screen above');
  await expect(saved.getByText('beta_field_1',{exact:true})).toHaveCount(0);
});
