import { Component, computed, effect, inject, signal, OnDestroy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api, AppendResult } from './api';

interface ScreenEntry {id:number;connectionId:number;instrument:string;name:string;source:string;createdAt:string;}
@Component({standalone:true,imports:[FormsModule,RouterLink],template:`
<div class="page-heading"><div><p class="eyebrow">SCREEN ANALYSIS</p><h1>Complete Call Trigger</h1><p>Register screen names and export the source fields used by each attribute's scripts.</p></div><a class="button secondary" href="/sample-screens.csv" download>Download sample CSV</a></div>
<section class="panel toolbar"><label for="trigger-connection">Oracle connection<select id="trigger-connection" [ngModel]="api.selectedId()" (ngModelChange)="changeConnection($event)" [disabled]="api.busy()"><option [ngValue]="null">Select an Oracle connection</option>@for(c of api.connections();track c.id){@if(c.databaseType==='ORACLE'){<option [ngValue]="c.id">{{c.name}} · {{c.schema}}</option>}}</select></label><span class="pill">{{screens().length}} SCREENS</span><button class="button secondary" (click)="refresh()" [disabled]="!oracle() || api.busy()">Refresh list</button></section>
@if(oracle()) {
<section class="panel toolbar"><label for="trigger-instrument">Instrument</label><select id="trigger-instrument" [ngModel]="instrument()" (ngModelChange)="changeInstrument($event)" [disabled]="api.busy() || loading()"><option value="">Select an instrument</option>@for(name of instruments();track name){<option [value]="name">{{name}}</option>}</select><span>{{instrumentScreens().length}} screen(s)</span></section>
<section class="panel"><label for="registration-instrument">Instrument name for registration</label><input id="registration-instrument" [(ngModel)]="registrationInstrument" maxlength="256" [disabled]="api.busy()" placeholder="Enter a new or existing instrument"><p>Use this instrument for manual entries or screen-only uploads. Files with an Instrument column use each row's value.</p></section>
<div class="three-column"><section class="panel"><h2>Upload screen names</h2><p>CSV, TXT or XLSX, up to 5 MB. Include Instrument and Screen Name headers. One file can contain multiple instruments. Excel reads the first sheet.</p><label class="file-label">Choose screen list<input type="file" accept=".csv,.txt,.xlsx" (change)="choose($event)" [disabled]="api.busy()"></label><small class="filename">{{file?.name || 'No file selected'}}</small><button class="button" (click)="upload()" [disabled]="!file || api.busy()">Upload & append</button></section>
<section class="panel"><h2>Add screens manually</h2><p>Enter one screen name per line. Names are case sensitive and must match the source dictionary.</p><label>Screen names<textarea [(ngModel)]="names" rows="4" [disabled]="api.busy()" placeholder="Portfolio Screen&#10;Account Screen"></textarea></label><button class="button secondary" (click)="append()" [disabled]="!names.trim() || !registrationInstrument.trim() || api.busy()">Append screens</button></section>
<section class="panel"><h2>Generate Excel workbook</h2><p>One tab per selected screen, with TAP Field Name, Complete Call Trigger, Drop Down Nature, Complete Rule and Rule Image columns. Extracted keywords appear on separate lines. Full rule text is retained when an image cannot be rendered.</p><p>{{selected().size}} screen(s) selected · maximum 200</p><button class="button" (click)="generate()" [disabled]="!selected().size || selected().size>200 || api.busy() || loading()">Generate workbook</button><p class="note">Long or unsupported Excel tab names are adjusted. The original screen name is retained in a header comment.</p></section></div>
<section class="panel table-panel"><div class="panel-title"><h2>Registered screens</h2><label class="search">Search screens<input [ngModel]="search()" (ngModelChange)="search.set($event)" placeholder="Search screen names"></label></div>
@if(loading()){<p role="status">Loading screens…</p>}
@if(loadError()){<p class="alert error" role="alert">{{loadError()}}</p>}
<div class="table-scroll"><table><thead><tr><th><input type="checkbox" aria-label="Select all visible screens" [checked]="allSelected()" [indeterminate]="someSelected() && !allSelected()" [disabled]="api.busy() || loading()" (change)="selectAll($any($event.target).checked)"></th><th>Screen name</th><th>Added from</th></tr></thead><tbody>
@for(s of filtered();track s.id){<tr><td><input type="checkbox" [attr.aria-label]="'Select '+s.name" [checked]="selected().has(s.id)" [disabled]="api.busy() || loading()" (change)="toggle(s.id,$any($event.target).checked)"></td><td>{{s.name}}</td><td>{{s.source}}</td></tr>}@empty{<tr><td colspan="3">{{!instrument()?'Select an instrument to view its screens.':search()?'No matching screens.':'Upload a list or add screen names to get started.'}}</td></tr>}
</tbody></table></div></section>
} @else {<section class="panel empty"><h2>Select an Oracle connection</h2><p>This feature reads screen scripts from the Oracle dictionary.</p><a class="button" routerLink="/connections">Manage connections</a></section>}
`})
export class TriggersComponent implements OnDestroy {
  api=inject(Api);screens=signal<ScreenEntry[]>([]);selected=signal(new Set<number>());search=signal('');
  instrument=signal('');registrationInstrument='';
  instruments=computed(()=>[...new Set(this.screens().map(s=>s.instrument))].sort((a,b)=>a.localeCompare(b)));
  instrumentScreens=computed(()=>this.screens().filter(s=>s.instrument===this.instrument()));
  loading=signal(false);loadError=signal('');file:File|null=null;names='';private version=0;private destroyed=false;
  oracle=computed(()=>this.api.selected()?.databaseType==='ORACLE');
  filtered=computed(()=>this.instrumentScreens().filter(s=>s.name.toLowerCase().includes(this.search().toLowerCase())));
  allSelected=computed(()=>this.filtered().length>0 && this.filtered().every(s=>this.selected().has(s.id)));
  someSelected=computed(()=>this.filtered().some(s=>this.selected().has(s.id)));
  constructor(){effect(()=>{const id=this.api.selectedId();const oracle=this.oracle();this.screens.set([]);this.selected.set(new Set());this.instrument.set('');this.registrationInstrument='';this.file=null;this.names='';void this.load(oracle?id:null);});}
  ngOnDestroy(){this.destroyed=true;this.version++;}
  changeConnection(id:number|null){void this.api.run(()=>this.api.select(id));}
  changeInstrument(name:string){this.instrument.set(name);this.registrationInstrument=name;this.selected.set(new Set());this.search.set('');}
  refresh(){void this.load(this.oracle()?this.api.selectedId():null);}
  private async load(id:number|null){
    const version=++this.version;this.loadError.set('');this.loading.set(false);if(id===null)return;
    this.loading.set(true);
    try{const rows=await this.api.get<ScreenEntry[]>(`/connections/${id}/screens`);if(version===this.version&&!this.destroyed){this.screens.set(rows);if(!rows.some(s=>s.instrument===this.instrument()))this.changeInstrument('');this.selected.update(ids=>new Set([...ids].filter(key=>rows.some(s=>s.id===key&&s.instrument===this.instrument()))));}}
    catch(e:any){if(version===this.version&&!this.destroyed)this.loadError.set(e?.error?.message??'Unable to load screens. Please retry.');}
    finally{if(version===this.version&&!this.destroyed)this.loading.set(false);}
  }
  toggle(id:number,checked:boolean){this.selected.update(ids=>{const next=new Set(ids);checked?next.add(id):next.delete(id);return next;});}
  selectAll(checked:boolean){this.selected.update(ids=>{const next=new Set(ids);this.filtered().forEach(s=>checked?next.add(s.id):next.delete(s.id));return next;});}
  choose(event:Event){this.file=(event.target as HTMLInputElement).files?.[0]??null;}
  private async added(id:number,instrument:string|null,result:AppendResult){
    if(this.destroyed || id!==this.api.selectedId())return;
    await this.load(id);if(this.destroyed||id!==this.api.selectedId())return;
    this.changeInstrument(instrument??(this.instrument()||this.instruments()[0]||''));this.api.message.set(`${result.added} screen(s) added. ${result.skipped} duplicate(s) skipped. ${result.total} registered screens ${instrument?'for '+instrument:'in this connection'}.`);
  }
  upload(){const id=this.api.selectedId(),file=this.file;if(id===null||!file||!this.oracle())return;void this.api.run(async()=>{
    const instrument=this.registrationInstrument.trim();
    if(file.size>5*1024*1024)throw Error('Upload a file no larger than 5 MB.');
    const body=new FormData();body.append('file',file);if(instrument)body.append('instrument',instrument);
    await this.added(id,null,await this.api.post<AppendResult>(`/connections/${id}/screens/import`,body));
  });}
  append(){const id=this.api.selectedId();if(id===null||!this.oracle())return;const names=this.names.split(/[\r\n]+/).map(s=>s.trim()).filter(Boolean);void this.api.run(async()=>{
    const instrument=this.registrationInstrument.trim();if(!instrument)throw Error('Enter an instrument name for registration.');
    await this.added(id,instrument,await this.api.post<AppendResult>(`/connections/${id}/screens`,{instrument,names}));if(!this.destroyed&&id===this.api.selectedId())this.names='';
  });}
  generate(){const id=this.api.selectedId();if(id===null||!this.oracle())return;const ids=this.instrumentScreens().filter(s=>this.selected().has(s.id)).map(s=>s.id);if(!ids.length||ids.length>200)return;void this.api.run(async()=>{
    const blob=await this.api.downloadTriggers(id,ids);if(this.destroyed)return;
    const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download='Complete_Call_Trigger.xlsx';document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
    this.api.message.set('Complete Call Trigger workbook downloaded.');
  });}
}
