import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
export interface Connection {id:number;name:string;databaseType:string;host:string;port:number;connectionType:string;databaseName:string;jdbcUrl:string;username:string;schema:string;passwordSaved:boolean;readOnly:boolean;}
export interface TableEntry {id:number;connectionId:number;schema:string;name:string;source:string;createdAt:string;fileType:string;loadStrategy:string;updateFrequency:string;}
export interface Status {status:string;database:string;migrationVersion:string;consoleEnabled:boolean;}
export interface TestResult {connected:boolean;message:string;tables:{schema:string;name:string}[];}
export interface AppendResult {added:number;skipped:number;total:number;}
@Injectable({providedIn:'root'})
export class Api {
  consoleUrl = window.location.port === '4300' ? 'http://127.0.0.1:8081/h2-console/' : '/h2-console/';
  private http=inject(HttpClient);
  connections=signal<Connection[]>([]);
  selectedId=signal<number|null>(null);
  selected=computed(()=>this.connections().find(c=>c.id===this.selectedId()));
  tables=signal<TableEntry[]>([]);
  status=signal<Status|null>(null);
  error=signal(''); message=signal(''); busy=signal(false);
  async init(){await this.run(async()=>{
    const [connections,status]=await Promise.all([this.get<Connection[]>('/connections'),this.get<Status>('/status')]);
    this.connections.set(connections);this.status.set(status);
    const saved=Number(localStorage.getItem('edac.connection'));
    await this.select(connections.find(c=>c.id===saved)?.id??connections[0]?.id??null);
  });}
  get<T>(path:string){return firstValueFrom(this.http.get<T>('/api'+path));}
  async post<T>(path:string,body:unknown){
    // Fetching the token materializes the XSRF cookie. Angular sends it on same-origin writes.
    await this.get('/csrf');
    return firstValueFrom(this.http.post<T>('/api'+path,body));
  }
  async downloadWorkbook(connectionId:number,tableIds:number[]){
    return this.download(`/connections/${connectionId}/mapping-workbook`,{tableIds});
  }
  async downloadTriggers(connectionId:number,screenIds:number[]){
    return this.download(`/connections/${connectionId}/screens/workbook`,{screenIds});
  }
  async downloadScripts(connectionId:number,settings:unknown){return this.download(`/connections/${connectionId}/extraction-scripts`,settings);}
  private async download(path:string,body:unknown){
    await this.get('/csrf');
    try{return await firstValueFrom(this.http.post('/api'+path,body,{responseType:'blob'}));}
    catch(error){if(error instanceof HttpErrorResponse && error.error instanceof Blob){let message='Workbook generation failed.';try{message=JSON.parse(await error.error.text()).message??message;}catch{}throw new Error(message);}throw error;}
  }
  async select(id:number|null){this.selectedId.set(id);this.tables.set([]);if(id!==null){localStorage.setItem('edac.connection',String(id));await this.refresh();}}
  async refresh(){const id=this.selectedId();if(id!==null){const rows=await this.get<TableEntry[]>(`/connections/${id}/tables`);if(this.selectedId()===id)this.tables.set(rows);}}
  async run(action:()=>Promise<void>){if(this.busy())return;this.busy.set(true);this.error.set('');this.message.set('');try{await action();}catch(error){this.error.set(error instanceof HttpErrorResponse?(error.error?.message??(error.status===0?'Backend unavailable. Start the Extraction Framework backend on port 8081.':`Request failed (${error.status}). Please retry.`)):error instanceof Error?error.message:'The operation failed.');}finally{this.busy.set(false);}}
  result(result:AppendResult){this.message.set(`${result.added} table(s) added. ${result.skipped} duplicate(s) skipped. ${result.total} table(s) in this connection.`);}
}
