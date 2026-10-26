import {Component,effect,inject,OnDestroy,signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {RouterLink} from '@angular/router';
import {Api} from './api';
import {ReportGridComponent,ReportGridPage} from './report-grid';

interface ProfileSelection {type:string;code:string;row:(string|null)[];}
interface ItemSelection {value:string;row:(string|null)[];}

@Component({standalone:true,imports:[FormsModule,RouterLink,ReportGridComponent],styleUrl:'./user-profile-matrix.css',templateUrl:'./user-profile-matrix.html'})
export class UserProfileMatrixComponent implements OnDestroy {
 api=inject(Api);
 connectionId:number|null=null;userCode='';
 matrix=signal<ReportGridPage|null>(null);detail=signal<ReportGridPage|null>(null);third=signal<ReportGridPage|null>(null);
 selection=signal<ProfileSelection|null>(null);item=signal<ItemSelection|null>(null);
 error=signal('');detailMessage=signal('');
 loadingMain=signal(false);loadingDetail=signal(false);loadingThird=signal(false);
 private version=0;private destroyed=false;
 constructor(){effect(()=>{const connections=this.api.connections();if(this.connectionId===null&&connections.length){this.connectionId=connections.find(connection=>connection.id===this.api.selectedId())?.id??connections[0].id;this.connectionChanged();}});}
 private base(connection=this.connectionId){return '/reports/user-profile-matrix/connections/'+connection;}
 private userQuery(){return 'userCode='+encodeURIComponent(this.userCode.trim());}
 private fail(error:any){this.error.set(error?.error?.message??error?.message??'Unable to load the report.');}
 private valid(version:number){return !this.destroyed&&version===this.version;}
 selectedUser(){return this.userCode.trim();}
 private clearChildren(){this.detail.set(null);this.third.set(null);this.item.set(null);this.detailMessage.set('');this.loadingDetail.set(false);this.loadingThird.set(false);}
 private clearMatrix(){this.version++;this.matrix.set(null);this.selection.set(null);this.clearChildren();this.loadingMain.set(false);this.error.set('');}
 connectionChanged(){this.clearMatrix();}
 userChanged(){this.clearMatrix();}
 async loadMatrix(page=0){
  if(this.connectionId===null||!this.userCode.trim()||this.loadingMain())return;
  const version=++this.version;this.matrix.set(null);this.selection.set(null);this.clearChildren();this.loadingMain.set(true);this.error.set('');
  try{const grid=await this.api.get<ReportGridPage>(this.base()+'/matrix?'+this.userQuery()+'&page='+page+'&size=100');if(this.valid(version))this.matrix.set(grid);}
  catch(error){if(this.valid(version))this.fail(error);}
  finally{if(this.valid(version))this.loadingMain.set(false);}
 }
 private cell(grid:ReportGridPage|null,row:(string|null)[],column:string){return grid?row[grid.columns.indexOf(column)]??'':'';}
 selectProfile(row:(string|null)[]){
  this.version++;this.clearChildren();this.error.set('');
  const type=this.cell(this.matrix(),row,'PROFILE_TYPE'),code=this.cell(this.matrix(),row,'PROFILE_CODE');
  this.selection.set({type,code,row});
  if(!['REPORT','DATA','FUNCTION_SECURITY','FORMAT','SCREEN'].includes(type)){this.detailMessage.set('No detail query configured for '+type+'.');return;}
  void this.loadDetail();
 }
 hasThirdGrid(){return this.selection()?.type==='FORMAT'||this.selection()?.type==='SCREEN';}
 detailTitle(){return ({REPORT:'Report profile details',DATA:'Data security details',FUNCTION_SECURITY:'Function security details',FORMAT:'Format profile details',SCREEN:'Screen profile details'} as Record<string,string>)[this.selection()?.type??'']??'Profile details';}
 async loadDetail(page=0){
  const selection=this.selection();if(!selection)return;
  const version=++this.version;this.detail.set(null);this.third.set(null);this.item.set(null);this.loadingThird.set(false);this.loadingDetail.set(true);this.detailMessage.set('');this.error.set('');
  try{const grid=await this.api.get<ReportGridPage>(this.base()+'/profiles/'+encodeURIComponent(selection.type)+'?'+this.userQuery()+'&profileCode='+encodeURIComponent(selection.code)+'&page='+page+'&size=100');if(this.valid(version)){if(grid.message)this.detailMessage.set(grid.message);else this.detail.set(grid);}}
  catch(error){if(this.valid(version))this.fail(error);}
  finally{if(this.valid(version))this.loadingDetail.set(false);}
 }
 selectItem(row:(string|null)[]){
  if(!this.hasThirdGrid())return;
  this.item.set({value:this.cell(this.detail(),row,this.selection()?.type==='FORMAT'?'FORMAT':'SCREEN_NAME'),row});void this.loadThird();
 }
 async loadThird(page=0){
  const selection=this.selection(),item=this.item();if(!selection||!item)return;
  const version=++this.version;this.third.set(null);this.loadingThird.set(true);this.error.set('');
  try{const grid=await this.api.get<ReportGridPage>(this.base()+'/profiles/'+encodeURIComponent(selection.type)+'/items?'+this.userQuery()+'&profileCode='+encodeURIComponent(selection.code)+'&item='+encodeURIComponent(item.value)+'&page='+page+'&size=100');if(this.valid(version))this.third.set(grid);}
  catch(error){if(this.valid(version))this.fail(error);}
  finally{if(this.valid(version))this.loadingThird.set(false);}
 }
 ngOnDestroy(){this.destroyed=true;this.version++;}
}
