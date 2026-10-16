import { Component, computed, effect, inject, signal, OnDestroy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';
interface Column {position:number;name:string;dataType:string;byteLength:number|null;characterLength:number|null;precision:number|null;scale:number|null;nullable:boolean;included:boolean;}
@Component({selector:'app-mapping',standalone:true,imports:[FormsModule],styleUrl:'./mapping.css',template:`
<div class="page-heading"><p class="eyebrow">PHASE 01</p><h1>Mapping workbook</h1><p>Select configured tables and choose the columns to include in your mapping workbook.</p></div>
<section class="panel"><label for="mapping-connection">Source database connection</label>
<select id="mapping-connection" [ngModel]="api.selectedId()" (ngModelChange)="changeConnection($event)" [disabled]="api.busy() || saving() || exporting()">
<option [ngValue]="null" disabled>Select a connection</option>
@for(c of api.connections();track c.id){<option [ngValue]="c.id">{{c.name}} — {{c.schema}}</option>}
</select><div class="export-actions"><button class="button" type="button" (click)="generate()" [disabled]="!selected().size || saving() || exporting() || api.busy()">{{exporting()?'Generating workbook…':'Generate mapping workbook'}}</button><span>{{selected().size}} table(s) selected</span></div>
@if(exportError()){<p role="alert" class="alert error">{{exportError()}}</p>}
@if(exportMessage()){<p role="status">{{exportMessage()}}</p>}
@if(api.selected()?.databaseType==='ORACLE'){<p class="note">Oracle uses standard metadata until the custom mapping query is supplied.</p>}
</section>
<div class="mapping-panels"><section class="panel configured-panel"><h2>Configured tables</h2>
@if(api.tables().length){<label class="check"><input type="checkbox" aria-label="Select all tables" [checked]="allSelected()" [indeterminate]="selected().size>0 && !allSelected()" [disabled]="saving() || exporting()" (change)="selectAll($any($event.target).checked)">Select all tables ({{selected().size}} / {{api.tables().length}})</label>
<div class="table-scroll"><table><thead><tr><th>Select</th><th>Schema</th><th>Table name</th></tr></thead><tbody>
@for(t of api.tables();track t.id){<tr><td><input type="checkbox" [attr.aria-label]="'Select '+t.schema+'.'+t.name" [checked]="selected().has(t.id)" [disabled]="saving() || exporting()" (change)="toggleTable(t.id,$any($event.target).checked)"></td><td>{{t.schema}}</td><td>{{t.name}}</td></tr>}
</tbody></table></div>}@else{<p>No tables configured for this connection. Add tables on the Table registry screen.</p>}
</section>
<section class="panel columns-panel"><h2>Column details @if(single();as t){— {{t.schema}}.{{t.name}}}</h2>
@if(selected().size>1){<p role="status">Column details cannot be shown when multiple tables are selected.</p>}
@else if(!single()){<p>Select one table to view its columns.</p>}
@else {
@if(loading()){<p role="status">Loading columns from {{api.selected()?.databaseType}}…</p>}
@if(error()){<p role="alert" class="alert error">{{error()}}</p>@if(!columns().length){<button (click)="reload()" [disabled]="loading()">Retry</button>}}
@if(columns().length){<p>Checked columns are included. Changes are saved automatically for this table and connection.</p>
<p role="status">{{saving()?'Saving column selection…':feedback()}}</p>
<div class="table-scroll"><table><thead><tr><th>Include</th><th>#</th><th>Column name</th><th>Source type</th><th>Bytes</th><th>Characters</th><th>Precision</th><th>Scale</th><th>Nullable</th></tr></thead><tbody>
@for(c of columns();track c.name){<tr [class.excluded]="!c.included"><td><input type="checkbox" [attr.aria-label]="'Include '+c.name" [checked]="c.included" [disabled]="saving() || exporting()" (change)="save(c,$any($event.target).checked)"></td><td>{{c.position}}</td><td>{{c.name}}</td><td>{{c.dataType}}</td><td>{{c.byteLength??'—'}}</td><td>{{c.characterLength??'—'}}</td><td>{{c.precision??'—'}}</td><td>{{c.scale??'—'}}</td><td>{{c.nullable?'Yes':'No'}}</td></tr>}
</tbody></table></div>}
}
</section></div>`})
export class MappingComponent implements OnDestroy {
 api=inject(Api); selected=signal(new Set<number>()); columns=signal<Column[]>([]);
 exporting=signal(false); exportError=signal(''); exportMessage=signal('');
 loading=signal(false); saving=signal(false); error=signal(''); feedback=signal('');
 private version=0; private connection:number|null=null; private destroyed=false;
 allSelected=computed(()=>this.api.tables().length>0 && this.selected().size===this.api.tables().length);
 single=computed(()=>this.selected().size===1?this.api.tables().find(t=>this.selected().has(t.id)):undefined);
 constructor(){effect(()=>{
   const id=this.api.selectedId(); const tables=this.api.tables();
   if(this.connection!==id){this.connection=id;this.selected.set(new Set());}
   const valid=new Set([...this.selected()].filter(id=>tables.some(t=>t.id===id)));
   if(valid.size!==this.selected().size)this.selected.set(valid);
   const table=this.single(); void this.load(id,table?.id);
 });}
 async generate(){
   const id=this.api.selectedId();if(id===null||!this.selected().size||this.saving()||this.exporting())return;
   const ids=this.api.tables().filter(t=>this.selected().has(t.id)).map(t=>t.id);
   this.exporting.set(true);this.exportError.set('');this.exportMessage.set('');
   try{const blob=await this.api.downloadWorkbook(id,ids);if(this.destroyed)return;
     const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download='Extraction_Framework_Mapping.xlsx';document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
     this.exportMessage.set('Mapping workbook downloaded. Only included columns were exported.');
   }catch(e:any){if(!this.destroyed)this.exportError.set(e?.message??'Workbook generation failed.');}
   finally{this.exporting.set(false);}
 }
 ngOnDestroy(){this.destroyed=true;this.version++;}
 changeConnection(id:number){void this.api.run(()=>this.api.select(id));}
 toggleTable(id:number,checked:boolean){const ids=new Set(this.selected());checked?ids.add(id):ids.delete(id);this.selected.set(ids);}
 selectAll(checked:boolean){this.selected.set(new Set(checked?this.api.tables().map(t=>t.id):[]));}
 reload(){void this.load(this.api.selectedId(),this.single()?.id);}
 private message(e:any){return e?.error?.message??'Unable to complete the request. Check the connection and retry.';}
 private async load(connection:number|null,table:number|undefined){
   const version=++this.version;this.columns.set([]);this.error.set('');this.feedback.set('');this.loading.set(false);
   if(connection===null || table===undefined)return;
   this.loading.set(true);
   try{const columns=await this.api.get<Column[]>('/connections/'+connection+'/tables/'+table+'/columns');if(version===this.version&&!this.destroyed)this.columns.set(columns);}
   catch(e){if(version===this.version&&!this.destroyed)this.error.set(this.message(e));}
   finally{if(version===this.version&&!this.destroyed)this.loading.set(false);}
 }
 async save(column:Column,included:boolean){
   const connection=this.api.selectedId(),table=this.single();if(connection===null||!table||this.saving())return;
   const version=this.version;this.saving.set(true);this.error.set('');this.feedback.set('');
   this.columns.update(rows=>rows.map(c=>c.name===column.name?{...c,included}:c));
   try{await this.api.post('/connections/'+connection+'/tables/'+table.id+'/columns/selection',{columnName:column.name,included});
     if(version===this.version&&!this.destroyed)this.feedback.set('Column selection saved.');
   }catch(e){if(version===this.version&&!this.destroyed){this.columns.update(rows=>rows.map(c=>c.name===column.name?column:c));this.error.set(this.message(e));}}
   finally{this.saving.set(false);}
 }
}
