import { Component, computed, effect, inject, signal, OnDestroy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api, AppendResult } from './api';

interface ScreenEntry {id:number;connectionId:number;instrument:string;name:string;source:string;createdAt:string;}
interface ReadableField {id:number;tableName:string;fieldName:string;}
interface ScreenField {tableName:string|null;fieldName:string;selectable:boolean;configured:boolean;}
@Component({standalone:true,imports:[FormsModule,RouterLink],template:`
<div class="page-heading"><div><p class="eyebrow">SCREEN ANALYSIS</p><h1>Complete Call Trigger</h1><p>Register screen names and export the source fields used by each attribute's scripts.</p></div><a class="button secondary" href="/sample-screens.csv" download>Download sample CSV</a></div>
<section class="panel toolbar"><label for="trigger-connection">Oracle connection<select id="trigger-connection" [ngModel]="api.selectedId()" (ngModelChange)="changeConnection($event)" [disabled]="api.busy()"><option [ngValue]="null">Select an Oracle connection</option>@for(c of api.connections();track c.id){@if(c.databaseType==='ORACLE'){<option [ngValue]="c.id">{{c.name}} · {{c.schema}}</option>}}</select></label><span class="pill">{{screens().length}} SCREENS</span><button class="button secondary" (click)="refresh()" [disabled]="!oracle() || api.busy()">Refresh list</button></section>
@if(oracle()) {
<section class="panel"><div class="panel-title"><h2>Readable fields</h2><a class="button secondary" href="/sample-readable-fields.csv" download>Download readable fields sample</a></div>
<p>Configure fields by CSV upload or select them from a screen's query results. Only saved Table Name and Field Name pairs are analyzed. This list is shared by all instruments and screens in the selected connection.</p>
<div class="three-column"><div><h3>Select from screen</h3><label for="field-screen">Screen for readable fields</label><select id="field-screen" [ngModel]="fieldScreenId()" (ngModelChange)="chooseFieldScreen($event)" [disabled]="api.busy() || loading()"><option [ngValue]="null">Select a screen</option>@for(s of screens();track s.id){<option [ngValue]="s.id">{{s.instrument}} / {{s.name}}</option>}</select><button class="button secondary" (click)="loadScreenFields()" [disabled]="fieldScreenId()===null || api.busy() || loading()">Load screen fields</button></div>
<div><h3>Upload CSV</h3><p>Use Table Name and Field Name headers.</p><label class="file-label">Choose readable fields<input type="file" accept=".csv" (change)="chooseReadable($event)" [disabled]="api.busy()"></label><small>{{readableFile?.name || 'No file selected'}}</small><button class="button secondary" (click)="uploadReadable()" [disabled]="!readableFile || api.busy()">Upload readable fields</button></div>
<div><p>{{readableFields().length}} readable field(s) configured.</p>@if(!readableFields().length){<p>Configure readable fields before generating a report.</p>}<label>Search readable fields<input [ngModel]="readableSearch()" (ngModelChange)="readableSearch.set($event)"></label></div></div>
@if(fieldsLoaded()){
<h3>Screen query results</h3><p>Check the required fields and save. Unchecked fields from this screen are removed from the connection's readable list. Fields without a supported table identity cannot be selected.</p>
<label>Search screen fields<input [ngModel]="candidateSearch()" (ngModelChange)="candidateSearch.set($event)" [disabled]="api.busy()"></label>
<div class="table-scroll"><table><thead><tr><th><input type="checkbox" aria-label="Select all screen fields" [checked]="allCandidatesSelected()" [disabled]="api.busy()" (change)="selectAllCandidates($any($event.target).checked)"></th><th>Table Name</th><th>Field Name</th><th>Status</th></tr></thead><tbody>
@for(f of visibleCandidates();track candidateKey(f)){<tr><td><input type="checkbox" [attr.aria-label]="'Include '+(f.tableName||'Unknown')+'.'+f.fieldName" [checked]="fieldSelection().has(candidateKey(f))" [disabled]="!f.selectable || api.busy()" (change)="toggleCandidate(f,$any($event.target).checked)"></td><td>{{f.tableName||'Not found'}}</td><td>{{f.fieldName}}</td><td>{{f.selectable?(f.configured?'Saved':'Available'):'Table or field name cannot be configured'}}</td></tr>}@empty{<tr><td colspan="4">No fields returned for this screen or search.</td></tr>}
</tbody></table></div><p>{{fieldSelection().size}} field(s) selected</p><button class="button secondary" (click)="saveFieldSelection()" [disabled]="api.busy()">Save screen field selection</button>
}
<div class="table-scroll"><table><thead><tr><th>Table Name</th><th>Field Name</th><th>Action</th></tr></thead><tbody>@for(f of visibleReadable();track f.id){<tr><td>{{f.tableName}}</td><td>{{f.fieldName}}</td><td><button class="text-button" [attr.aria-label]="'Remove '+f.tableName+'.'+f.fieldName" (click)="removeReadable(f)" [disabled]="api.busy()">Remove</button></td></tr>}</tbody></table></div></section>
<section class="panel toolbar"><label for="trigger-instrument">Instrument</label><select id="trigger-instrument" [ngModel]="instrument()" (ngModelChange)="changeInstrument($event)" [disabled]="api.busy() || loading()"><option value="">Select an instrument</option>@for(name of instruments();track name){<option [value]="name">{{name}}</option>}</select><span>{{instrumentScreens().length}} screen(s)</span></section>
<section class="panel"><label for="registration-instrument">Instrument name for registration</label><input id="registration-instrument" [(ngModel)]="registrationInstrument" maxlength="256" [disabled]="api.busy()" placeholder="Enter a new or existing instrument"><p>Use this instrument for manual entries or screen-only uploads. Files with an Instrument column use each row's value.</p></section>
<div class="three-column"><section class="panel"><h2>Upload screen names</h2><p>CSV, TXT or XLSX, up to 5 MB. Include Instrument and Screen Name headers. One file can contain multiple instruments. Excel reads the first sheet.</p><label class="file-label">Choose screen list<input type="file" accept=".csv,.txt,.xlsx" (change)="choose($event)" [disabled]="api.busy()"></label><small class="filename">{{file?.name || 'No file selected'}}</small><button class="button" (click)="upload()" [disabled]="!file || api.busy()">Upload & append</button></section>
<section class="panel"><h2>Add screens manually</h2><p>Enter one screen name per line. Names are case sensitive and must match the source dictionary.</p><label>Screen names<textarea [(ngModel)]="names" rows="4" [disabled]="api.busy()" placeholder="Portfolio Screen&#10;Account Screen"></textarea></label><button class="button secondary" (click)="append()" [disabled]="!names.trim() || !registrationInstrument.trim() || api.busy()">Append screens</button></section>
<section class="panel"><h2>Generate Excel workbook</h2><p>One tab per selected screen, with TAP Field Name, Complete Call Trigger, Drop Down Nature, Complete Rule, Rule Image and Table Name columns. Extracted keywords appear on separate lines. Full rule text is retained when an image cannot be rendered.</p><p>{{selected().size}} screen(s) selected · maximum 200</p><button class="button" (click)="generate()" [disabled]="!readableFields().length || !selected().size || selected().size>200 || api.busy() || loading()">Generate workbook</button><p class="note">Long or unsupported Excel tab names are adjusted. The original screen name is retained in a header comment.</p></section></div>
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
  readableFields=signal<ReadableField[]>([]);readableSearch=signal('');readableFile:File|null=null;
  fieldScreenId=signal<number|null>(null);candidateFields=signal<ScreenField[]>([]);fieldSelection=signal(new Set<string>());fieldsLoaded=signal(false);candidateSearch=signal('');
  visibleCandidates=computed(()=>this.candidateFields().filter(f=>((f.tableName||'')+'.'+f.fieldName).toLowerCase().includes(this.candidateSearch().toLowerCase())));
  allCandidatesSelected=computed(()=>{const fields=this.visibleCandidates().filter(f=>f.selectable);return fields.length>0&&fields.every(f=>this.fieldSelection().has(this.candidateKey(f)));});
  visibleReadable=computed(()=>this.readableFields().filter(f=>(f.tableName+'.'+f.fieldName).toLowerCase().includes(this.readableSearch().toLowerCase())));
  instrument=signal('');registrationInstrument='';
  instruments=computed(()=>[...new Set(this.screens().map(s=>s.instrument))].sort((a,b)=>a.localeCompare(b)));
  instrumentScreens=computed(()=>this.screens().filter(s=>s.instrument===this.instrument()));
  loading=signal(false);loadError=signal('');file:File|null=null;names='';private version=0;private destroyed=false;
  oracle=computed(()=>this.api.selected()?.databaseType==='ORACLE');
  filtered=computed(()=>this.instrumentScreens().filter(s=>s.name.toLowerCase().includes(this.search().toLowerCase())));
  allSelected=computed(()=>this.filtered().length>0 && this.filtered().every(s=>this.selected().has(s.id)));
  someSelected=computed(()=>this.filtered().some(s=>this.selected().has(s.id)));
  constructor(){effect(()=>{const id=this.api.selectedId();const oracle=this.oracle();this.screens.set([]);this.readableFields.set([]);this.readableFile=null;this.readableSearch.set('');this.chooseFieldScreen(null);this.selected.set(new Set());this.instrument.set('');this.registrationInstrument='';this.file=null;this.names='';void this.load(oracle?id:null);});}
  ngOnDestroy(){this.destroyed=true;this.version++;}
  changeConnection(id:number|null){void this.api.run(()=>this.api.select(id));}
  changeInstrument(name:string){this.instrument.set(name);this.registrationInstrument=name;this.selected.set(new Set());this.search.set('');}
  refresh(){void this.load(this.oracle()?this.api.selectedId():null);}
  private async load(id:number|null){
    const version=++this.version;this.loadError.set('');this.loading.set(false);if(id===null)return;
    this.loading.set(true);
    try{const [rows,fields]=await Promise.all([this.api.get<ScreenEntry[]>(`/connections/${id}/screens`),this.api.get<ReadableField[]>(`/connections/${id}/screens/readable-fields`)]);if(version===this.version&&!this.destroyed){this.screens.set(rows);this.readableFields.set(fields);if(!rows.some(s=>s.instrument===this.instrument()))this.changeInstrument('');this.selected.update(ids=>new Set([...ids].filter(key=>rows.some(s=>s.id===key&&s.instrument===this.instrument()))));}}
    catch(e:any){if(version===this.version&&!this.destroyed)this.loadError.set(e?.error?.message??'Unable to load screens. Please retry.');}
    finally{if(version===this.version&&!this.destroyed)this.loading.set(false);}
  }
  toggle(id:number,checked:boolean){this.selected.update(ids=>{const next=new Set(ids);checked?next.add(id):next.delete(id);return next;});}
  selectAll(checked:boolean){this.selected.update(ids=>{const next=new Set(ids);this.filtered().forEach(s=>checked?next.add(s.id):next.delete(s.id));return next;});}
  choose(event:Event){this.file=(event.target as HTMLInputElement).files?.[0]??null;}
  chooseReadable(event:Event){this.readableFile=(event.target as HTMLInputElement).files?.[0]??null;}
  private async refreshReadable(id:number){const fields=await this.api.get<ReadableField[]>(`/connections/${id}/screens/readable-fields`);if(!this.destroyed&&id===this.api.selectedId())this.readableFields.set(fields);}
  chooseFieldScreen(id:number|null){this.fieldScreenId.set(id);this.candidateFields.set([]);this.fieldSelection.set(new Set());this.fieldsLoaded.set(false);this.candidateSearch.set('');}
  candidateKey(field:ScreenField){return JSON.stringify([field.tableName,field.fieldName]);}
  toggleCandidate(field:ScreenField,checked:boolean){if(!field.selectable)return;this.fieldSelection.update(ids=>{const next=new Set(ids);checked?next.add(this.candidateKey(field)):next.delete(this.candidateKey(field));return next;});}
  selectAllCandidates(checked:boolean){this.fieldSelection.update(ids=>{const next=new Set(ids);this.visibleCandidates().filter(f=>f.selectable).forEach(f=>checked?next.add(this.candidateKey(f)):next.delete(this.candidateKey(f)));return next;});}
  private async fetchScreenFields(id:number,screen:number){
    const fields=await this.api.get<ScreenField[]>(`/connections/${id}/screens/${screen}/readable-fields`);
    if(this.destroyed||id!==this.api.selectedId()||screen!==this.fieldScreenId())return;
    this.candidateFields.set(fields);this.fieldSelection.set(new Set(fields.filter(f=>f.selectable&&f.configured).map(f=>this.candidateKey(f))));this.fieldsLoaded.set(true);
  }
  loadScreenFields(){const id=this.api.selectedId(),screen=this.fieldScreenId();if(id===null||screen===null||!this.oracle())return;this.candidateFields.set([]);this.fieldsLoaded.set(false);this.fieldSelection.set(new Set());void this.api.run(()=>this.fetchScreenFields(id,screen));}
  saveFieldSelection(){const id=this.api.selectedId(),screen=this.fieldScreenId();if(id===null||screen===null||!this.fieldsLoaded()||!this.oracle())return;
    const fields=this.candidateFields().filter(f=>f.selectable&&this.fieldSelection().has(this.candidateKey(f))).map(f=>({tableName:f.tableName??'',fieldName:f.fieldName}));
    void this.api.run(async()=>{await this.api.post(`/connections/${id}/screens/${screen}/readable-fields`,{fields});await Promise.all([this.refreshReadable(id),this.fetchScreenFields(id,screen)]);if(!this.destroyed&&id===this.api.selectedId())this.api.message.set('Screen field selection saved to the connection readable list.');});
  }
  uploadReadable(){const id=this.api.selectedId(),file=this.readableFile;if(id===null||!file||!this.oracle())return;void this.api.run(async()=>{
    if(file.size>5*1024*1024)throw Error('Upload a file no larger than 5 MB.');const body=new FormData();body.append('file',file);
    const result=await this.api.post<AppendResult>(`/connections/${id}/screens/readable-fields/import`,body);await this.refreshReadable(id);
    if(!this.destroyed&&id===this.api.selectedId()){this.chooseFieldScreen(null);this.api.message.set(`${result.added} readable field(s) added; ${result.skipped} duplicate(s) skipped.`);}
  });}
  removeReadable(field:ReadableField){const id=this.api.selectedId();if(id===null||!this.oracle())return;void this.api.run(async()=>{await this.api.post(`/connections/${id}/screens/readable-fields/${field.id}/remove`,{});await this.refreshReadable(id);if(!this.destroyed&&id===this.api.selectedId())this.chooseFieldScreen(null);});}
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
  generate(){const id=this.api.selectedId();if(id===null||!this.oracle()||!this.readableFields().length)return;const ids=this.instrumentScreens().filter(s=>this.selected().has(s.id)).map(s=>s.id);if(!ids.length||ids.length>200)return;void this.api.run(async()=>{
    const blob=await this.api.downloadTriggers(id,ids);if(this.destroyed)return;
    const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download='Complete_Call_Trigger.xlsx';document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
    this.api.message.set('Complete Call Trigger workbook downloaded.');
  });}
}
